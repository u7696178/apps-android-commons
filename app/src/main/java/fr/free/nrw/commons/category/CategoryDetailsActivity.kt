package fr.free.nrw.commons.category

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fr.free.nrw.commons.BuildConfig.COMMONS_URL
import fr.free.nrw.commons.Media
import fr.free.nrw.commons.R
import fr.free.nrw.commons.ViewPagerAdapter
import fr.free.nrw.commons.contributions.ContributionController
import fr.free.nrw.commons.databinding.ActivityCategoryDetailsBinding
import fr.free.nrw.commons.explore.categories.media.CategoriesMediaFragment
import fr.free.nrw.commons.explore.categories.parent.ParentCategoriesFragment
import fr.free.nrw.commons.explore.categories.sub.SubCategoriesFragment
import fr.free.nrw.commons.filepicker.FilePicker
import fr.free.nrw.commons.media.MediaDetailPagerFragment
import fr.free.nrw.commons.media.MediaDetailProvider
import fr.free.nrw.commons.theme.BaseActivity
import fr.free.nrw.commons.utils.applyEdgeToEdgeAllInsets
import fr.free.nrw.commons.utils.handleWebUrl
import fr.free.nrw.commons.wikidata.model.WikiSite
import fr.free.nrw.commons.wikidata.model.page.PageTitle
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.Manifest.permission
import fr.free.nrw.commons.utils.ViewUtil.showShortToast

/**
 * This activity displays details of a particular category
 * Its generic and simply takes the name of category name in its start intent to load all images, subcategories in
 * a particular category on wikimedia commons.
 */
class CategoryDetailsActivity :
    BaseActivity(),
    MediaDetailProvider,
    CategoryImagesCallback {
    private lateinit var supportFragmentManager: FragmentManager
    private lateinit var categoriesMediaFragment: CategoriesMediaFragment
    private var mediaDetails: MediaDetailPagerFragment? = null
    private var categoryName: String? = null
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private lateinit var binding: ActivityCategoryDetailsBinding

    private var fabOpen: Animation? = null
    private var fabClose: Animation? = null
    private var rotateForward: Animation? = null
    private var rotateBackward: Animation? = null
    private var isFabOpen = false

    @Inject
    lateinit var categoryViewModelFactory: CategoryDetailsViewModel.ViewModelFactory

    @Inject
    lateinit var contributionController: ContributionController

    private val viewModel: CategoryDetailsViewModel by viewModels<CategoryDetailsViewModel> { categoryViewModelFactory }

    private lateinit var inAppCameraLocationPermissionLauncher: ActivityResultLauncher<Array<String>>

    private val galleryPickLauncherForResult =
        registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
            contributionController.handleActivityResultWithCallback(this) { callbacks: FilePicker.Callbacks ->
                contributionController.onPictureReturnedFromGallery(result, this, callbacks)
            }
        }

    private val customSelectorLauncherForResult =
        registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
            contributionController.handleActivityResultWithCallback(this) { callbacks: FilePicker.Callbacks ->
                contributionController.onPictureReturnedFromCustomSelector(result, this, callbacks)
            }
        }

    private val cameraPickLauncherForResult =
        registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
            contributionController.handleActivityResultWithCallback(this) { callbacks: FilePicker.Callbacks ->
                contributionController.onPictureReturnedFromCamera(result, this, callbacks)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCategoryDetailsBinding.inflate(layoutInflater)
        val view = binding.root
        applyEdgeToEdgeAllInsets(view)
        setContentView(view)
        supportFragmentManager = getSupportFragmentManager()
        viewPagerAdapter = ViewPagerAdapter(this, supportFragmentManager)
        binding.viewPager.adapter = viewPagerAdapter
        binding.viewPager.offscreenPageLimit = 2
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        setSupportActionBar(binding.toolbarBinding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTabs()
        setPageTitle()
        inAppCameraLocationPermissionLauncher =
            registerForActivityResult(RequestMultiplePermissions()) { result ->
                val areAllGranted = result.values.all { it }

                if (areAllGranted) {
                    contributionController.locationPermissionCallback?.onLocationPermissionGranted()
                } else {
                    this.let { currentActivity ->
                        if (currentActivity.shouldShowRequestPermissionRationale(
                                permission.ACCESS_FINE_LOCATION,
                            )
                        ) {
                            contributionController.handleShowRationaleFlowCameraLocation(
                                currentActivity,
                                inAppCameraLocationPermissionLauncher, // Pass launcher
                                cameraPickLauncherForResult,
                            )
                        } else {
                            contributionController.locationPermissionCallback?.onLocationPermissionDenied(
                                currentActivity.getString(
                                    R.string.in_app_camera_location_permission_denied,
                                ),
                            )
                        }
                    }
                }
            }
        initializeFabAnimations()
        binding.fabPlus.setOnClickListener { animateFAB(isFabOpen) }

        binding.fabCamera.setOnClickListener {
            contributionController.initiateCameraPick(
                this,
                inAppCameraLocationPermissionLauncher,
                cameraPickLauncherForResult,
            )
            animateFAB(isFabOpen)
        }
        binding.fabCamera.setOnLongClickListener {
            showShortToast(this, R.string.add_contribution_from_camera)
            true
        }

        binding.fabGallery.setOnClickListener {
            contributionController.initiateGalleryPick(this, galleryPickLauncherForResult, true)
            animateFAB(isFabOpen)
        }
        binding.fabGallery.setOnLongClickListener {
            showShortToast(this, R.string.menu_from_gallery)
            true
        }

        binding.fabCustomGallery.setOnClickListener {
            contributionController.initiateCustomGalleryPickWithPermission(this, customSelectorLauncherForResult)
            animateFAB(isFabOpen)
        }
        binding.fabCustomGallery.setOnLongClickListener {
            showShortToast(this, R.string.custom_selector_title)
            true
        }


        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bookmarkState.collect {
                    invalidateOptionsMenu()
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    binding.contributionFab.visibility = View.VISIBLE
                    if (supportFragmentManager.backStackEntryCount == 1) {
                        binding.tabLayout.visibility = View.VISIBLE
                        binding.viewPager.visibility = View.VISIBLE
                        binding.mediaContainer.visibility = View.GONE
                        binding.fabLayout.visibility = View.VISIBLE
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
    }

    /**
     * This activity contains 3 tabs and a viewpager. This method is used to set the titles of tab,
     * Set the fragments according to the tab selected in the viewPager.
     */
    private fun setTabs() {
        categoriesMediaFragment = CategoriesMediaFragment()
        val subCategoryListFragment = SubCategoriesFragment()
        val parentCategoriesFragment = ParentCategoriesFragment()
        categoryName = intent?.getStringExtra("categoryName")
        if (intent != null && categoryName != null) {
            val arguments =
                Bundle().apply {
                    putString("categoryName", categoryName)
                }
            categoriesMediaFragment.arguments = arguments
            subCategoryListFragment.arguments = arguments
            parentCategoriesFragment.arguments = arguments

            viewModel.onCheckIfBookmarked(categoryName!!)
        }

        viewPagerAdapter.setTabs(
            R.string.title_for_media to categoriesMediaFragment,
            R.string.title_for_subcategories to subCategoryListFragment,
            R.string.title_for_parent_categories to parentCategoriesFragment,
        )
        viewPagerAdapter.notifyDataSetChanged()
    }

    /**
     * This method loads the animations that will be used when the user
     * interacts with any part of the add fab
     */
    private fun initializeFabAnimations() {
        fabOpen = AnimationUtils.loadAnimation(this, R.anim.fab_open)
        fabClose = AnimationUtils.loadAnimation(this, R.anim.fab_close)
        rotateForward = AnimationUtils.loadAnimation(this, R.anim.rotate_forward)
        rotateBackward = AnimationUtils.loadAnimation(this, R.anim.rotate_backward)
    }

    /**
     * This method runs the animation based if the fab is open or closed
     */
    private fun animateFAB(isFabOpen: Boolean) {
        this.isFabOpen = !isFabOpen
        if (binding.fabPlus.isShown) {
            if (isFabOpen) {
                binding.fabPlus.startAnimation(rotateBackward)
                binding.fabCamera.startAnimation(fabClose)
                binding.fabGallery.startAnimation(fabClose)
                binding.fabCustomGallery.startAnimation(fabClose)
                binding.fabCamera.hide()
                binding.fabGallery.hide()
                binding.fabCustomGallery.hide()
            } else {
                binding.fabPlus.startAnimation(rotateForward)
                binding.fabCamera.startAnimation(fabOpen)
                binding.fabGallery.startAnimation(fabOpen)
                binding.fabCustomGallery.startAnimation(fabOpen)
                binding.fabCamera.show()
                binding.fabGallery.show()
                binding.fabCustomGallery.show()
            }
            this.isFabOpen = !isFabOpen
        }
    }

    /**
     * Gets the passed categoryName from the intents and displays it as the page title
     */
    private fun setPageTitle() {
        intent?.getStringExtra("categoryName")?.let {
            title = it
        }
    }

    /**
     * This method is called onClick of media inside category details (CategoryImageListFragment).
     */
    override fun onMediaClicked(position: Int) {
        binding.tabLayout.visibility = View.GONE
        binding.viewPager.visibility = View.GONE
        binding.mediaContainer.visibility = View.VISIBLE
        if (isFabOpen) animateFAB(isFabOpen)
        binding.fabLayout.visibility = View.GONE
        if (mediaDetails == null || mediaDetails?.isVisible == false) {
            // set isFeaturedImage true for featured images, to include author field on media detail
            mediaDetails = MediaDetailPagerFragment.newInstance(false, true)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.mediaContainer, mediaDetails!!)
                .addToBackStack(null)
                .commit()
            supportFragmentManager.executePendingTransactions()
        }
        mediaDetails?.showImage(position)
    }

    companion object {
        /**
         * Consumers should be simply using this method to use this activity.
         * @param context  A Context of the application package implementing this class.
         * @param categoryName Name of the category for displaying its details
         */
        fun startYourself(
            context: Context?,
            categoryName: String,
        ) {
            val intent =
                Intent(context, CategoryDetailsActivity::class.java).apply {
                    putExtra("categoryName", categoryName)
                }
            context?.startActivity(intent)
        }
    }

    /**
     * This method is called mediaDetailPagerFragment. It returns the Media Object at that Index
     * @param i It is the index of which media object is to be returned which is same as
     *          current index of viewPager.
     * @return Media Object
     */
    override fun getMediaAtPosition(i: Int): Media? = categoriesMediaFragment.getMediaAtPosition(i)

    /**
     * This method is called on from getCount of MediaDetailPagerFragment
     * The viewpager will contain same number of media items as that of media elements in adapter.
     * @return Total Media count in the adapter
     */
    override fun getTotalMediaCount(): Int = categoriesMediaFragment.getTotalMediaCount()

    override fun getContributionStateAt(position: Int): Int? = null

    /**
     * Reload media detail fragment once media is nominated
     *
     * @param index item position that has been nominated
     */
    override fun refreshNominatedMedia(index: Int) {
        if (supportFragmentManager.backStackEntryCount == 1) {
            onBackPressedDispatcher.onBackPressed()
            onMediaClicked(index)
        }
    }

    /**
     * This method inflates the menu in the toolbar
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.fragment_category_detail, menu)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * This method handles the logic on ItemSelect in toolbar menu
     * Currently only 1 choice is available to open category details page in browser
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.menu_browser_current_category -> {
                val title = PageTitle(CATEGORY_PREFIX + categoryName, WikiSite(COMMONS_URL))

                handleWebUrl(this, Uri.parse(title.canonicalUri))
                true
            }

            R.id.menu_bookmark_current_category -> {
                categoryName?.let {
                    viewModel.onBookmarkClick(categoryName = it)
                }
                true
            }

            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.run {
            val bookmarkMenuItem = findItem(R.id.menu_bookmark_current_category)
            if (bookmarkMenuItem != null) {
                val icon =
                    if (viewModel.bookmarkState.value) {
                        R.drawable.menu_ic_round_star_filled_24px
                    } else {
                        R.drawable.menu_ic_round_star_border_24px
                    }

                bookmarkMenuItem.setIcon(icon)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * This method is called on success of API call for Images inside a category.
     * The viewpager will notified that number of items have changed.
     */
    override fun viewPagerNotifyDataSetChanged() {
        mediaDetails?.notifyDataSetChanged()
    }
}
