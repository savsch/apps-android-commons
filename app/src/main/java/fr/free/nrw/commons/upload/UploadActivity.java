package fr.free.nrw.commons.upload;

import static fr.free.nrw.commons.contributions.ContributionController.ACTION_INTERNAL_UPLOADS;
import static fr.free.nrw.commons.utils.PermissionUtils.checkPermissionsAndPerformAction;
import static fr.free.nrw.commons.utils.PermissionUtils.getPERMISSIONS_STORAGE;
import static fr.free.nrw.commons.wikidata.WikidataConstants.PLACE_OBJECT;
import static fr.free.nrw.commons.wikidata.WikidataConstants.SELECTED_NEARBY_PLACE;
import static fr.free.nrw.commons.wikidata.WikidataConstants.SELECTED_NEARBY_PLACE_CATEGORY;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.work.ExistingWorkPolicy;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.auth.LoginActivity;
import fr.free.nrw.commons.auth.SessionManager;
import fr.free.nrw.commons.contributions.ContributionController;
import fr.free.nrw.commons.databinding.ActivityUploadBinding;
import fr.free.nrw.commons.filepicker.Constants.RequestCodes;
import fr.free.nrw.commons.filepicker.UploadableFile;
import fr.free.nrw.commons.kvstore.BasicKvStore;
import fr.free.nrw.commons.kvstore.JsonKvStore;
import fr.free.nrw.commons.location.LatLng;
import fr.free.nrw.commons.location.LocationPermissionsHelper;
import fr.free.nrw.commons.location.LocationServiceManager;
import fr.free.nrw.commons.mwapi.UserClient;
import fr.free.nrw.commons.nearby.Place;
import fr.free.nrw.commons.settings.Prefs;
import fr.free.nrw.commons.theme.BaseActivity;
import fr.free.nrw.commons.upload.UploadBaseFragment.Callback;
import fr.free.nrw.commons.upload.categories.UploadCategoriesFragment;
import fr.free.nrw.commons.upload.depicts.DepictsFragment;
import fr.free.nrw.commons.upload.license.MediaLicenseFragment;
import fr.free.nrw.commons.upload.mediaDetails.UploadMediaDetailFragment;
import fr.free.nrw.commons.upload.mediaDetails.UploadMediaDetailFragment.UploadMediaDetailFragmentCallback;
import fr.free.nrw.commons.upload.mediaDetails.UploadMediaPresenter;
import fr.free.nrw.commons.upload.worker.WorkRequestHelper;
import fr.free.nrw.commons.utils.DialogUtil;
import fr.free.nrw.commons.utils.PermissionUtils;
import fr.free.nrw.commons.utils.ViewUtil;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import timber.log.Timber;

public class UploadActivity extends BaseActivity implements
    UploadContract.View, UploadBaseFragment.Callback, ThumbnailsAdapter.OnThumbnailDeletedListener {

    @Inject
    ContributionController contributionController;
    @Inject
    @Named("default_preferences")
    JsonKvStore directKvStore;
    @Inject
    UploadContract.UserActionListener presenter;
    @Inject
    SessionManager sessionManager;
    @Inject
    UserClient userClient;
    @Inject
    LocationServiceManager locationManager;

    private boolean isTitleExpanded = true;

    private CompositeDisposable compositeDisposable;
    private ProgressDialog progressDialog;
    private UploadImageAdapter uploadImagesAdapter;
    private List<UploadBaseFragment> fragments;
    private UploadCategoriesFragment uploadCategoriesFragment;
    private DepictsFragment depictsFragment;
    private MediaLicenseFragment mediaLicenseFragment;
    private ThumbnailsAdapter thumbnailsAdapter;
    BasicKvStore store;
    private Place place;
    private LatLng prevLocation;
    private LatLng currLocation;
    private static boolean uploadIsOfAPlace = false;
    private boolean isInAppCameraUpload;
    private List<UploadableFile> uploadableFiles = Collections.emptyList();
    private int currentSelectedPosition = 0;
    /*
     Checks for if multiple files selected
     */
    private boolean isMultipleFilesSelected = false;

    public static final String EXTRA_FILES = "commons_image_exta";
    public static final String LOCATION_BEFORE_IMAGE_CAPTURE = "user_location_before_image_capture";
    public static final String IN_APP_CAMERA_UPLOAD = "in_app_camera_upload";

    /**
     * Stores all nearby places found and related users response for
     * each place while uploading media
     */
    public static HashMap<Place,Boolean> nearbyPopupAnswers;

    /**
     * A private boolean variable to control whether a permissions dialog should be shown
     * when necessary. Initially, it is set to `true`, indicating that the permissions dialog
     * should be displayed if permissions are missing and it is first time calling
     * `checkStoragePermissions` method.
     * This variable is used in the `checkStoragePermissions` method to determine whether to
     * show a permissions dialog to the user if the required permissions are not granted.
     * If `showPermissionsDialog` is set to `true` and the necessary permissions are missing,
     * a permissions dialog will be displayed to request the required permissions. If set
     * to `false`, the dialog won't be shown.
     *
     * @see UploadActivity#checkStoragePermissions()
     */
    private boolean showPermissionsDialog = true;

    /**
     * Whether fragments have been saved.
     */
    private boolean isFragmentsSaved = false;
    
    public static final String keyForCurrentUploadImagesSize = "CurrentUploadImagesSize";
    public static final String storeNameForCurrentUploadImagesSize = "CurrentUploadImageQualities";

    private ActivityUploadBinding binding;

    @SuppressLint("CheckResult")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityUploadBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        /*
         If Configuration of device is changed then get the new fragments
         created by the system and populate the fragments ArrayList
         */
        if (savedInstanceState != null) {
            isFragmentsSaved = true;
            final List<Fragment> fragmentList = getSupportFragmentManager().getFragments();
            fragments = new ArrayList<>();
            for (final Fragment fragment : fragmentList) {
                fragments.add((UploadBaseFragment) fragment);
            }
        }


        compositeDisposable = new CompositeDisposable();
        init();
        binding.rlContainerTitle.setOnClickListener(v -> onRlContainerTitleClicked());
        nearbyPopupAnswers = new HashMap<>();
        //getting the current dpi of the device and if it is less than 320dp i.e. overlapping
        //threshold, thumbnails automatically minimizes
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float dpi = (metrics.widthPixels)/(metrics.density);
        if (dpi<=321) {
            onRlContainerTitleClicked();
        }
        if (PermissionUtils.hasPermission(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION})) {
            locationManager.registerLocationManager();
        }
        locationManager.requestLocationUpdatesFromProvider(LocationManager.GPS_PROVIDER);
        locationManager.requestLocationUpdatesFromProvider(LocationManager.NETWORK_PROVIDER);
        store = new BasicKvStore(this, storeNameForCurrentUploadImagesSize);
        store.clearAll();
        checkStoragePermissions();

    }

    private void init() {
        initProgressDialog();
        initViewPager();
        initThumbnailsRecyclerView();
        //And init other things you need to
    }

    private void initProgressDialog() {
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getString(R.string.please_wait));
        progressDialog.setCancelable(false);
    }

    private void initThumbnailsRecyclerView() {
        binding.rvThumbnails.setLayoutManager(new LinearLayoutManager(this,
            LinearLayoutManager.HORIZONTAL, false));
        thumbnailsAdapter = new ThumbnailsAdapter(() -> currentSelectedPosition);
        thumbnailsAdapter.setOnThumbnailDeletedListener(this);
        binding.rvThumbnails.setAdapter(thumbnailsAdapter);

    }

    private void initViewPager() {
        uploadImagesAdapter = new UploadImageAdapter(getSupportFragmentManager());
        binding.vpUpload.setAdapter(uploadImagesAdapter);
        binding.vpUpload.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(final int position, final float positionOffset,
                final int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(final int position) {
                currentSelectedPosition = position;
                if (position >= uploadableFiles.size()) {
                    binding.cvContainerTopCard.setVisibility(View.GONE);
                } else {
                    thumbnailsAdapter.notifyDataSetChanged();
                    binding.cvContainerTopCard.setVisibility(View.VISIBLE);
                }

            }

            @Override
            public void onPageScrollStateChanged(final int state) {

            }
        });
    }

    @Override
    public boolean isLoggedIn() {
        return sessionManager.isUserLoggedIn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        presenter.onAttachView(this);
        if (!isLoggedIn()) {
            askUserToLogIn();
        }
        checkBlockStatus();
    }

    /**
     * Makes API call to check if user is blocked from Commons. If the user is blocked, a snackbar
     * is created to notify the user
     */
    protected void checkBlockStatus() {
        compositeDisposable.add(userClient.isUserBlockedFromCommons()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .filter(result -> result)
            .subscribe(result -> DialogUtil.showAlertDialog(
                this,
                getString(R.string.block_notification_title),
                getString(R.string.block_notification),
                getString(R.string.ok),
                this::finish)));
    }

    public void checkStoragePermissions() {
        // Check if all required permissions are granted
        final boolean hasAllPermissions = PermissionUtils.hasPermission(this, getPERMISSIONS_STORAGE());
        final boolean hasPartialAccess = PermissionUtils.hasPartialAccess(this);
        if (hasAllPermissions || hasPartialAccess) {
            // All required permissions are granted, so enable UI elements and perform actions
            receiveSharedItems();
            binding.cvContainerTopCard.setVisibility(View.VISIBLE);
        } else {
            // Permissions are missing
            binding.cvContainerTopCard.setVisibility(View.INVISIBLE);
            if(showPermissionsDialog){
                checkPermissionsAndPerformAction(this,
                    () -> {
                        binding.cvContainerTopCard.setVisibility(View.VISIBLE);
                        this.receiveSharedItems();
                    },() -> {
                        this.showPermissionsDialog = true;
                        this.checkStoragePermissions();
                        },
                    R.string.storage_permission_title,
                    R.string.write_storage_permission_rationale_for_image_share,
                    getPERMISSIONS_STORAGE());
            }
        }
        /* If all permissions are not granted and a dialog is already showing on screen
         showPermissionsDialog will set to false making it not show dialog again onResume,
         but if user Denies any permission showPermissionsDialog will be to true
         and permissions dialog will be shown again.
         */
        this.showPermissionsDialog = hasAllPermissions ;
    }

    @Override
    protected void onStop() {
        // Resetting setImageCancelled to false
        setImageCancelled(false);
        super.onStop();
    }

    @Override
    public void returnToMainActivity() {
        finish();
    }

    /**
     * go to the uploadProgress activity to check the status of uploading
     */
    @Override
    public void goToUploadProgressActivity() {
        startActivity(new Intent(this, UploadProgressActivity.class));
    }

    /**
     * Show/Hide the progress dialog
     */
    @Override
    public void showProgress(final boolean shouldShow) {
        if (shouldShow) {
            if (!progressDialog.isShowing()) {
                progressDialog.show();
            }
        } else {
            if (progressDialog != null && !isFinishing()) {
                progressDialog.dismiss();
            }
        }
    }

    @Override
    public int getIndexInViewFlipper(final UploadBaseFragment fragment) {
        return fragments.indexOf(fragment);
    }

    @Override
    public int getTotalNumberOfSteps() {
        return fragments.size();
    }

    @Override
    public boolean isWLMUpload() {
        return place!=null && place.isMonument();
    }

    @Override
    public void showMessage(final int messageResourceId) {
        ViewUtil.showLongToast(this, messageResourceId);
    }

    @Override
    public List<UploadableFile> getUploadableFiles() {
        return uploadableFiles;
    }

    @Override
    public void showHideTopCard(final boolean shouldShow) {
        binding.llContainerTopCard.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onUploadMediaDeleted(final int index) {
        fragments.remove(index);//Remove the corresponding fragment
        uploadableFiles.remove(index);//Remove the files from the list
        thumbnailsAdapter.notifyItemRemoved(index); //Notify the thumbnails adapter
        uploadImagesAdapter.notifyDataSetChanged(); //Notify the ViewPager
    }

    @Override
    public void updateTopCardTitle() {
        binding.tvTopCardTitle.setText(getResources()
            .getQuantityString(R.plurals.upload_count_title, uploadableFiles.size(), uploadableFiles.size()));
    }

    @Override
    public void makeUploadRequest() {
        WorkRequestHelper.Companion.makeOneTimeWorkRequest(getApplicationContext(),
            ExistingWorkPolicy.APPEND_OR_REPLACE);
    }

    @Override
    public void askUserToLogIn() {
        Timber.d("current session is null, asking user to login");
        ViewUtil.showLongToast(this, getString(R.string.user_not_logged_in));
        final Intent loginIntent = new Intent(UploadActivity.this, LoginActivity.class);
        startActivity(loginIntent);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
        @NonNull final String[] permissions,
        @NonNull final int[] grantResults) {
        boolean areAllGranted = false;
        if (requestCode == RequestCodes.STORAGE) {
            if (VERSION.SDK_INT >= VERSION_CODES.M) {
                for (int i = 0; i < grantResults.length; i++) {
                    final String permission = permissions[i];
                    areAllGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                    if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                        final boolean showRationale = shouldShowRequestPermissionRationale(permission);
                        if (!showRationale) {
                            DialogUtil.showAlertDialog(this,
                                getString(R.string.storage_permissions_denied),
                                getString(R.string.unable_to_share_upload_item),
                                getString(android.R.string.ok),
                                this::finish);
                        } else {
                            DialogUtil.showAlertDialog(this,
                                getString(R.string.storage_permission_title),
                                getString(
                                    R.string.write_storage_permission_rationale_for_image_share),
                                getString(android.R.string.ok),
                                this::checkStoragePermissions);
                        }
                    }
                }

                if (areAllGranted) {
                    receiveSharedItems();
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Sets the flag indicating whether the upload is of a specific place.
     *
     * @param uploadOfAPlace a boolean value indicating whether the upload is of place.
     */
    public static void setUploadIsOfAPlace(final boolean uploadOfAPlace) {
        uploadIsOfAPlace = uploadOfAPlace;
    }

    private void receiveSharedItems() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            receiveExternalSharedItems();
        } else if (ACTION_INTERNAL_UPLOADS.equals(action)) {
            receiveInternalSharedItems();
        }

        if (uploadableFiles == null || uploadableFiles.isEmpty()) {
            handleNullMedia();
        } else {
            //Show thumbnails
            if (uploadableFiles.size() > 1){
                if(!defaultKvStore.getBoolean("hasAlreadyLaunchedCategoriesDialog")){//If there is only file, no need to show the image thumbnails
                    showAlertDialogForCategories();
                }
                if (uploadableFiles.size() > 3 &&
                    !defaultKvStore.getBoolean("hasAlreadyLaunchedBigMultiupload")){
                    showAlertForBattery();
                }
                thumbnailsAdapter.setUploadableFiles(uploadableFiles);
            } else {
                binding.llContainerTopCard.setVisibility(View.GONE);
            }
            binding.tvTopCardTitle.setText(getResources()
                .getQuantityString(R.plurals.upload_count_title, uploadableFiles.size(), uploadableFiles.size()));


            if(fragments == null){
                fragments = new ArrayList<>();
            }


            for (final UploadableFile uploadableFile : uploadableFiles) {
                final UploadMediaDetailFragment uploadMediaDetailFragment = new UploadMediaDetailFragment();

                if (!uploadIsOfAPlace) {
                    handleLocation();
                    uploadMediaDetailFragment.setImageToBeUploaded(uploadableFile, place, currLocation);
                    locationManager.unregisterLocationManager();
                } else {
                    uploadMediaDetailFragment.setImageToBeUploaded(uploadableFile, place, currLocation);
                }

                final UploadMediaDetailFragmentCallback uploadMediaDetailFragmentCallback = new UploadMediaDetailFragmentCallback() {
                    @Override
                    public void deletePictureAtIndex(final int index) {
                        store.putInt(keyForCurrentUploadImagesSize,
                            (store.getInt(keyForCurrentUploadImagesSize) - 1));
                        presenter.deletePictureAtIndex(index);
                    }

                    /**
                     * Changes the thumbnail of an UploadableFile at the specified index.
                     * This method updates the list of uploadableFiles by replacing the UploadableFile
                     * at the given index with a new UploadableFile created from the provided file path.
                     * After updating the list, it notifies the RecyclerView's adapter to refresh its data,
                     * ensuring that the thumbnail change is reflected in the UI.
                     *
                     * @param index The index of the UploadableFile to be updated.
                     * @param filepath The file path of the new thumbnail image.
                     */
                    @Override
                    public void changeThumbnail(final int index, final String filepath) {
                        uploadableFiles.remove(index);
                        uploadableFiles.add(index, new UploadableFile(new File(filepath)));
                        binding.rvThumbnails.getAdapter().notifyDataSetChanged();
                    }

                    @Override
                    public void onNextButtonClicked(final int index) {
                        UploadActivity.this.onNextButtonClicked(index);
                    }

                    @Override
                    public void onPreviousButtonClicked(final int index) {
                        UploadActivity.this.onPreviousButtonClicked(index);
                    }

                    @Override
                    public void showProgress(final boolean shouldShow) {
                        UploadActivity.this.showProgress(shouldShow);
                    }

                    @Override
                    public int getIndexInViewFlipper(final UploadBaseFragment fragment) {
                        return fragments.indexOf(fragment);
                    }

                    @Override
                    public int getTotalNumberOfSteps() {
                        return fragments.size();
                    }

                    @Override
                    public boolean isWLMUpload() {
                        return place!=null && place.isMonument();
                    }
                };

                if(isFragmentsSaved){
                    final UploadMediaDetailFragment fragment = (UploadMediaDetailFragment) fragments.get(0);
                    fragment.setCallback(uploadMediaDetailFragmentCallback);
                }else{
                    uploadMediaDetailFragment.setCallback(uploadMediaDetailFragmentCallback);
                    fragments.add(uploadMediaDetailFragment);
                }

            }

            //If fragments are not created, create them and add them to the fragments ArrayList
            if(!isFragmentsSaved){
                uploadCategoriesFragment = new UploadCategoriesFragment();
                if (place != null) {
                    final Bundle categoryBundle = new Bundle();
                    categoryBundle.putString(SELECTED_NEARBY_PLACE_CATEGORY, place.getCategory());
                    uploadCategoriesFragment.setArguments(categoryBundle);
                }

                uploadCategoriesFragment.setCallback(this);

                depictsFragment = new DepictsFragment();
                final Bundle placeBundle = new Bundle();
                placeBundle.putParcelable(SELECTED_NEARBY_PLACE, place);
                depictsFragment.setArguments(placeBundle);
                depictsFragment.setCallback(this);

                mediaLicenseFragment = new MediaLicenseFragment();
                mediaLicenseFragment.setCallback(this);

                fragments.add(depictsFragment);
                fragments.add(uploadCategoriesFragment);
                fragments.add(mediaLicenseFragment);

            }else{
                for(int i=1;i<fragments.size();i++){
                    fragments.get(i).setCallback(new Callback() {
                        @Override
                        public void onNextButtonClicked(final int index) {
                            if (index < fragments.size() - 1) {
                                binding.vpUpload.setCurrentItem(index + 1, false);
                                fragments.get(index + 1).onBecameVisible();
                                ((LinearLayoutManager) binding.rvThumbnails.getLayoutManager())
                                    .scrollToPositionWithOffset((index > 0) ? index-1 : 0, 0);
                            } else {
                                presenter.handleSubmit();
                            }

                        }
                        @Override
                        public void onPreviousButtonClicked(final int index) {
                            if (index != 0) {
                                binding.vpUpload.setCurrentItem(index - 1, true);
                                fragments.get(index - 1).onBecameVisible();
                                ((LinearLayoutManager) binding.rvThumbnails.getLayoutManager())
                                    .scrollToPositionWithOffset((index > 3) ? index-2 : 0, 0);
                            }
                        }
                        @Override
                        public void showProgress(final boolean shouldShow) {
                            if (shouldShow) {
                                if (!progressDialog.isShowing()) {
                                    progressDialog.show();
                                }
                            } else {
                                if (progressDialog != null && !isFinishing()) {
                                    progressDialog.dismiss();
                                }
                            }
                        }
                        @Override
                        public int getIndexInViewFlipper(final UploadBaseFragment fragment) {
                            return fragments.indexOf(fragment);
                        }

                        @Override
                        public int getTotalNumberOfSteps() {
                            return fragments.size();
                        }

                        @Override
                        public boolean isWLMUpload() {
                            return place!=null && place.isMonument();
                        }
                    });
                }
            }

            uploadImagesAdapter.setFragments(fragments);
            binding.vpUpload.setOffscreenPageLimit(fragments.size());

        }
        // Saving size of uploadableFiles
        store.putInt(keyForCurrentUploadImagesSize, uploadableFiles.size());
    }

    /**
     * Users may uncheck Location tag from the Manage EXIF tags setting any time.
     * So, their location must not be shared in this case.
     *
     */
    private boolean isLocationTagUncheckedInTheSettings() {
        final Set<String> prefExifTags = defaultKvStore.getStringSet(Prefs.MANAGED_EXIF_TAGS);
        if (prefExifTags.contains(getString(R.string.exif_tag_location))) {
            return false;
        }
        return true;
    }

    /**
     * Changes current image when one image upload is cancelled, to highlight next image in the top thumbnail.
     * Fixes: <a href="https://github.com/commons-app/apps-android-commons/issues/5511">Issue</a>
     *
     * @param index Index of image to be removed
     * @param maxSize Max size of the {@code uploadableFiles}
     */
    @Override
    public void highlightNextImageOnCancelledImage(final int index, final int maxSize) {
        if (binding.vpUpload != null && index < (maxSize)) {
            binding.vpUpload.setCurrentItem(index + 1, false);
            binding.vpUpload.setCurrentItem(index, false);
        }
    }

    /**
     * Used to check if user has cancelled upload of any image in current upload
     * so that location compare doesn't show up again in same upload.
     * Fixes: <a href="https://github.com/commons-app/apps-android-commons/issues/5511">Issue</a>
     *
     * @param isCancelled Is true when user has cancelled upload of any image in current upload
     */
    @Override
    public void setImageCancelled(final boolean isCancelled) {
        final BasicKvStore basicKvStore = new BasicKvStore(this,"IsAnyImageCancelled");
        basicKvStore.putBoolean("IsAnyImageCancelled", isCancelled);
    }

    /**
     * Calculate the difference between current location and
     * location recorded before capturing the image
     *
     */
    private float getLocationDifference(final LatLng currLocation, final LatLng prevLocation) {
        if (prevLocation == null) {
            return 0.0f;
        }
        final float[] distance = new float[2];
        Location.distanceBetween(
            currLocation.getLatitude(), currLocation.getLongitude(),
            prevLocation.getLatitude(), prevLocation.getLongitude(), distance);
        return distance[0];
    }

    private void receiveExternalSharedItems() {
        uploadableFiles = contributionController.handleExternalImagesPicked(this, getIntent());
    }

    private void receiveInternalSharedItems() {
        final Intent intent = getIntent();

        Timber.d("Received intent %s with action %s", intent.toString(), intent.getAction());

        uploadableFiles = intent.getParcelableArrayListExtra(EXTRA_FILES);
        isMultipleFilesSelected = uploadableFiles.size() > 1;
        Timber.i("Received multiple upload %s", uploadableFiles.size());

        place = intent.getParcelableExtra(PLACE_OBJECT);
        prevLocation = intent.getParcelableExtra(LOCATION_BEFORE_IMAGE_CAPTURE);
        isInAppCameraUpload = intent.getBooleanExtra(IN_APP_CAMERA_UPLOAD, false);
        resetDirectPrefs();
    }

    /**
     * Returns if multiple files selected or not.
     */
    public boolean getIsMultipleFilesSelected() {
        return isMultipleFilesSelected;
    }

    public void resetDirectPrefs() {
        directKvStore.remove(PLACE_OBJECT);
    }

    /**
     * Handle null URI from the received intent.
     * Current implementation will simply show a toast and finish the upload activity.
     */
    private void handleNullMedia() {
        ViewUtil.showLongToast(this, R.string.error_processing_image);
        finish();
    }


    @Override
    public void showAlertDialog(final int messageResourceId, @NonNull final Runnable onPositiveClick) {
        DialogUtil.showAlertDialog(this,
            "",
            getString(messageResourceId),
            getString(R.string.ok),
            onPositiveClick);
    }

    @Override
    public void onNextButtonClicked(final int index) {
        if (index < fragments.size() - 1) {
            binding.vpUpload.setCurrentItem(index + 1, false);
            fragments.get(index + 1).onBecameVisible();
            ((LinearLayoutManager) binding.rvThumbnails.getLayoutManager())
                .scrollToPositionWithOffset((index > 0) ? index - 1 : 0, 0);
            if (index < fragments.size() - 4) {
                // check image quality if next image exists
                presenter.checkImageQuality(index + 1);
            }
        } else {
            presenter.handleSubmit();
        }
    }

    @Override
    public void onPreviousButtonClicked(final int index) {
        if (index != 0) {
            binding.vpUpload.setCurrentItem(index - 1, true);
            fragments.get(index - 1).onBecameVisible();
            ((LinearLayoutManager) binding.rvThumbnails.getLayoutManager())
                .scrollToPositionWithOffset((index > 3) ? index-2 : 0, 0);
            if ((index != 1) && ((index - 1) < uploadableFiles.size())) {
                // Shows the top card if it was hidden because of the last image being deleted and
                // now the user has hit previous button to go back to the media details
                showHideTopCard(true);
            }
        }
    }

    @Override
    public void onThumbnailDeleted(final int position) {
        presenter.deletePictureAtIndex(position);
    }

    /**
     * The adapter used to show image upload intermediate fragments
     */


    private static class UploadImageAdapter extends FragmentStatePagerAdapter {
        List<UploadBaseFragment> fragments;

        public UploadImageAdapter(final FragmentManager fragmentManager) {
            super(fragmentManager);
            this.fragments = new ArrayList<>();
        }

        public void setFragments(final List<UploadBaseFragment> fragments) {
            this.fragments = fragments;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Fragment getItem(final int position) {
            return fragments.get(position);
        }

        @Override
        public int getCount() {
            return fragments.size();
        }

        @Override
        public int getItemPosition(@NonNull final Object item) {
            return PagerAdapter.POSITION_NONE;
        }
    }



    public void onRlContainerTitleClicked() {
        binding.rvThumbnails.setVisibility(isTitleExpanded ? View.GONE : View.VISIBLE);
        isTitleExpanded = !isTitleExpanded;
        binding.ibToggleTopCard.setRotation(binding.ibToggleTopCard.getRotation() + 180);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Resetting all values in store by clearing them
        store.clearAll();
        presenter.onDetachView();
        compositeDisposable.clear();
        fragments = null;
        uploadImagesAdapter = null;
        if (mediaLicenseFragment != null) {
            mediaLicenseFragment.setCallback(null);
        }
        if (uploadCategoriesFragment != null) {
            uploadCategoriesFragment.setCallback(null);
        }

    }

    /**
     * Get the value of the showPermissionDialog variable.
     *
     * @return {@code true} if Permission Dialog should be shown, {@code false} otherwise.
     */
    public boolean isShowPermissionsDialog() {
        return showPermissionsDialog;
    }

    /**
     * Set the value of the showPermissionDialog variable.
     *
     * @param showPermissionsDialog {@code true} to indicate to show
     * Permissions Dialog if permissions are missing, {@code false} otherwise.
     */
    public void setShowPermissionsDialog(final boolean showPermissionsDialog) {
        this.showPermissionsDialog = showPermissionsDialog;
    }

    /**
     * Overrides the back button to make sure the user is prepared to lose their progress
     */
    @Override
    public void onBackPressed() {
        DialogUtil.showAlertDialog(this,
            getString(R.string.back_button_warning),
            getString(R.string.back_button_warning_desc),
            getString(R.string.back_button_continue),
            getString(R.string.back_button_warning),
            null,
            this::finish
        );
    }

    /**
     * If the user uploads more than 1 file informs that
     * depictions/categories apply to all pictures of a multi upload.
     * This method takes no arguments and does not return any value.
     * It shows the AlertDialog and continues the flow of uploads.
     */
    private void showAlertDialogForCategories() {
        UploadMediaPresenter.isCategoriesDialogShowing = true;
        // Inflate the custom layout
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(R.layout.activity_upload_categories_dialog, null);
        final CheckBox checkBox = view.findViewById(R.id.categories_checkbox);
        // Create the alert dialog
        final AlertDialog alertDialog = new AlertDialog.Builder(this)
            .setView(view)
            .setTitle(getString(R.string.multiple_files_depiction_header))
            .setMessage(getString(R.string.multiple_files_depiction))
            .setPositiveButton("OK", (dialog, which) -> {
                if (checkBox.isChecked()) {
                    // Save the user's choice to not show the dialog again
                    defaultKvStore.putBoolean("hasAlreadyLaunchedCategoriesDialog", true);
                }
                presenter.checkImageQuality(0);

                UploadMediaPresenter.isCategoriesDialogShowing = false;
            })
            .setNegativeButton("", null)
            .create();
        alertDialog.show();
        }


    /** Suggest users to turn battery optimisation off when uploading
     * more than a few files. That's because we have noticed that
     * many-files uploads have a much higher probability of failing
     * than uploads with less files. Show the dialog for Android 6
     * and above as the ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
     * intent was added in API level 23
     */
    private void showAlertForBattery(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // When battery-optimisation dialog is shown don't show the image quality dialog
                UploadMediaPresenter.isBatteryDialogShowing = true;
                DialogUtil.showAlertDialog(
                    this,
                    getString(R.string.unrestricted_battery_mode),
                    getString(R.string.suggest_unrestricted_mode),
                    getString(R.string.title_activity_settings),
                    getString(R.string.cancel),
                    () -> {
                        /* Since opening the right settings page might be device dependent, using
                           https://github.com/WaseemSabir/BatteryPermissionHelper
                           directly appeared like a promising idea.
                           However, this simply closed the popup and did not make
                           the settings page appear on a Pixel as well as a Xiaomi device.
                           Used the standard intent instead of using this library as
                           it shows a list of all the apps on the device and allows users to
                           turn battery optimisation off.
                         */
                        final Intent batteryOptimisationSettingsIntent = new Intent(
                            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(batteryOptimisationSettingsIntent);
                        // calling checkImageQuality after battery dialog is interacted with
                        // so that 2 dialogs do not pop up simultaneously

                        UploadMediaPresenter.isBatteryDialogShowing = false;
                    },
                    () -> {
                        UploadMediaPresenter.isBatteryDialogShowing = false;
                    }
                );
                defaultKvStore.putBoolean("hasAlreadyLaunchedBigMultiupload", true);
            }
        }

    /**
     * If the permission for Location is turned on and certain
     * conditions are met, returns current location of the user.
     */
    private void handleLocation(){
        final LocationPermissionsHelper locationPermissionsHelper = new LocationPermissionsHelper(
            this, locationManager, null);
        if (locationPermissionsHelper.isLocationAccessToAppsTurnedOn()) {
            currLocation = locationManager.getLastLocation();
        }

        if (currLocation != null) {
            final float locationDifference = getLocationDifference(currLocation, prevLocation);
            final boolean isLocationTagUnchecked = isLocationTagUncheckedInTheSettings();
                    /* Remove location if the user has unchecked the Location EXIF tag in the
                       Manage EXIF Tags setting or turned "Record location for in-app shots" off.
                       Also, location information is discarded if the difference between
                       current location and location recorded just before capturing the image
                       is greater than 100 meters */
            if (isLocationTagUnchecked || locationDifference > 100
                || !defaultKvStore.getBoolean("inAppCameraLocationPref")
                || !isInAppCameraUpload) {
                currLocation = null;
            }
        }
    }
}
