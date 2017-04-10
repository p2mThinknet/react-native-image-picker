package com.imagepicker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.afollestad.materialcamera.MaterialCamera;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import dagger.ObjectGraph;

import static android.app.Activity.RESULT_OK;

public class ImagePickerModule extends ReactContextBaseJavaModule implements ActivityEventListener {

  static final int REQUEST_LAUNCH_IMAGE_CAPTURE = 1;
  static final int REQUEST_LAUNCH_IMAGE_LIBRARY = 2;
  static final int REQUEST_LAUNCH_VIDEO_LIBRARY = 3;
  static final int REQUEST_LAUNCH_VIDEO_CAPTURE = 4;

  static final  int REQUEST_IMAGE_CROPPING = 5;

  private final ReactApplicationContext mReactContext;

  private Uri mCameraCaptureURI;
  private Uri mCropImagedUri;
  private Callback mCallback;
  private Boolean noData = false;
  private Boolean tmpImage;
  private Boolean allowEditing = false;
  private Boolean pickVideo = false;
  private int maxWidth = 0;
  private int maxHeight = 0;
  private int aspectX = 0;
  private int aspectY = 0;
  private int quality = 100;
  private int angle = 0;
  private Boolean forceAngle = false;
  private int videoQuality = 1;
  private int videoDurationLimit = 0;
  WritableMap response;
  private Activity currentActivity;

  //compress
  public ProgressDialog progressBar;
  @Inject
  FFmpeg ffmpeg;

  private String newFilePath = "";
  private double videoDuration = 0;
  private final static int CAMERA_RQ = 6969;
  //////////////////////////////

  public ImagePickerModule(ReactApplicationContext reactContext) {
    super(reactContext);

    reactContext.addActivityEventListener(this);

    mReactContext = reactContext;
    ObjectGraph.create(new DaggerDependencyModule(mReactContext)).inject(this);
    loadFFMpegBinary();
    initUI();
  }

  private void initUI() {

  }

  private void loadFFMpegBinary() {
    try {
      ffmpeg.loadBinary(new LoadBinaryResponseHandler() {
        @Override
        public void onFailure() {
          showUnsupportedExceptionDialog();
        }
      });
    } catch (FFmpegNotSupportedException e) {
      showUnsupportedExceptionDialog();
    }
  }

  private void showUnsupportedExceptionDialog() {
    /*new AlertDialog.Builder(mReactContext)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(getString(R.string.device_not_supported))
            .setMessage(getString(R.string.device_not_supported_message))
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                mReactContext.this.finish();
              }
            })
            .create()
            .show();*/
  }

  @Override
  public String getName() {
    return "ImagePickerManager";
  }

  @ReactMethod
  public void showImagePicker(final ReadableMap options, final Callback callback) {
    currentActivity = getCurrentActivity();

    if (currentActivity == null) {
      response = Arguments.createMap();
      response.putString("error", "can't find current Activity");
      callback.invoke(response);
      return;
    }

    final List<String> titles = new ArrayList<String>();
    final List<String> actions = new ArrayList<String>();

    if (options.hasKey("takePhotoButtonTitle")
            && options.getString("takePhotoButtonTitle") != null
            && !options.getString("takePhotoButtonTitle").isEmpty()
            && isCameraAvailable()) {
      titles.add(options.getString("takePhotoButtonTitle"));
      actions.add("photo");
    }
    if (options.hasKey("chooseFromLibraryButtonTitle")
            && options.getString("chooseFromLibraryButtonTitle") != null
            && !options.getString("chooseFromLibraryButtonTitle").isEmpty()) {
      titles.add(options.getString("chooseFromLibraryButtonTitle"));
      actions.add("library");
    }

    String cancelButtonTitle = options.getString("cancelButtonTitle");
    if (options.hasKey("customButtons")) {
      ReadableMap buttons = options.getMap("customButtons");
      ReadableMapKeySetIterator it = buttons.keySetIterator();
      // Keep the current size as the iterator returns the keys in the reverse order they are defined
      int currentIndex = titles.size();
      while (it.hasNextKey()) {
        String key = it.nextKey();

        titles.add(currentIndex, key);
        actions.add(currentIndex, buttons.getString(key));
      }
    }

    titles.add(cancelButtonTitle);
    actions.add("cancel");

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(currentActivity,
            android.R.layout.select_dialog_item, titles);
    AlertDialog.Builder builder = new AlertDialog.Builder(currentActivity);
    if (options.hasKey("title") && options.getString("title") != null && !options.getString("title").isEmpty()) {
      builder.setTitle(options.getString("title"));
    }

    builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int index) {
        String action = actions.get(index);
        response = Arguments.createMap();

        switch (action) {
          case "photo":
            launchCamera(options, callback);
            break;
          case "library":
            launchImageLibrary(options, callback);
            break;
          case "cancel":
            response.putBoolean("didCancel", true);
            callback.invoke(response);
            break;
          default: // custom button
            response.putString("customButton", action);
            callback.invoke(response);
        }
      }
    });

    final AlertDialog dialog = builder.create();
    /**
     * override onCancel method to callback cancel in case of a touch outside of
     * the dialog or the BACK key pressed
     */
    dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
      @Override
      public void onCancel(DialogInterface dialog) {
        response = Arguments.createMap();
        dialog.dismiss();
        response.putBoolean("didCancel", true);
        callback.invoke(response);
      }
    });
    dialog.show();
  }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchCamera(final ReadableMap options, final Callback callback) {
    int requestCode;
    Intent cameraIntent;
    response = Arguments.createMap();

    if (!isCameraAvailable()) {
        response.putString("error", "Camera not available");
        callback.invoke(response);
        return;
    }

    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      response.putString("error", "can't find current Activity");
      callback.invoke(response);
      return;
    }

    if (!permissionsCheck(currentActivity)) {
      return;
    }

    parseOptions(options);

    if (pickVideo == true) {
      requestCode = REQUEST_LAUNCH_VIDEO_CAPTURE;
      /*File saveFolder = new File(Environment.getExternalStorageDirectory(), "jzfpvideopick");
      if (!saveFolder.mkdirs())
        throw new RuntimeException("Unable to create save directory, make sure WRITE_EXTERNAL_STORAGE permission is granted.");*/
      mCallback = callback;
      new MaterialCamera(currentActivity)
              //.saveDir(saveFolder)
              .allowChangeCamera(true)
              .qualityProfile(MaterialCamera.QUALITY_HIGH)
              .showPortraitWarning(false)
              .countdownMinutes(1.0f/3.0f)
              .start(CAMERA_RQ);
    } else {
      requestCode = REQUEST_LAUNCH_IMAGE_CAPTURE;
      cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

      // we create a tmp file to save the result
      File imageFile = createNewFile(true);
      mCameraCaptureURI = Uri.fromFile(imageFile);
      cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(imageFile));

      if (allowEditing == true) {
        /*cameraIntent.putExtra("crop", "true");
        cameraIntent.putExtra("aspectX", aspectX);
        cameraIntent.putExtra("aspectY", aspectY);*/
      }


    if (cameraIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
      response.putString("error", "Cannot launch camera");
      callback.invoke(response);
      return;
    }

    mCallback = callback;

    try {
      currentActivity.startActivityForResult(cameraIntent, requestCode);
    } catch (ActivityNotFoundException e) {
      e.printStackTrace();
      response = Arguments.createMap();
      response.putString("error", "Cannot launch camera");
      callback.invoke(response);
    }
    }
  }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchImageLibrary(final ReadableMap options, final Callback callback) {
    int requestCode;
    Intent libraryIntent;
    Activity currentActivity = getCurrentActivity();

    if (currentActivity == null) {
      response = Arguments.createMap();
      response.putString("error", "can't find current Activity");
      callback.invoke(response);
      return;
    }

    if (!permissionsCheck(currentActivity)) {
      return;
    }

    parseOptions(options);

    if (pickVideo == true) {
      requestCode = REQUEST_LAUNCH_VIDEO_LIBRARY;
      libraryIntent = new Intent(Intent.ACTION_PICK);
      libraryIntent.setType("video/*");
      if (libraryIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
        response = Arguments.createMap();
        response.putString("error", "Cannot launch photo library");
        callback.invoke(response);
        return;
      }

      mCallback = callback;

      try {
        currentActivity.startActivityForResult(libraryIntent, requestCode);
      } catch (ActivityNotFoundException e) {
        e.printStackTrace();
        response = Arguments.createMap();
        response.putString("error", "Cannot launch photo library");
        callback.invoke(response);
      }
    } else {
      if (allowEditing == true) {
        libraryIntent = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        if (libraryIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
          response.putString("error", "Cannot launch photo library");
          callback.invoke(response);
          return;
        }

        mCallback = callback;

        try {
          currentActivity.startActivityForResult(libraryIntent, REQUEST_LAUNCH_IMAGE_LIBRARY);
        } catch(ActivityNotFoundException e) {
          e.printStackTrace();
        }
      }
      else {
        //multi image pick
        Intent i = new Intent();//Action.ACTION_MULTIPLE_PICK
        i.setClass(currentActivity, CustomGalleryActivity.class);
        mCallback = callback;
        currentActivity.startActivityForResult(i, 200);
      }

    }
  }

  private WritableMap getImageFromPath(String path) {
    WritableMap image = new WritableNativeMap();
    BitmapFactory.Options imageOptions = new BitmapFactory.Options();
    imageOptions.inJustDecodeBounds = true;

    BitmapFactory.decodeFile(path, imageOptions);

    int initialWidth = imageOptions.outWidth;
    int initialHeight = imageOptions.outHeight;


    File resized = getResizedImage(path, initialWidth, initialHeight);
    if (resized == null) {
      image.putString("error", "Can't resize the image");
    } else {
      path = resized.getAbsolutePath();
    }
    image.putString("path", path);
    image.putString("originpath", path);

    if (!noData) {
      image.putString("data", getBase64StringFromFile(path));
    }

    putExtraFileInfo(path, image);
    return image;
  }

  private WritableMap getImage(Uri uri, boolean resolvePath) {
    WritableMap image = new WritableNativeMap();
    String path = uri.getPath();

    if (resolvePath) {
      path = RealPathUtil.getRealPathFromURI(currentActivity, uri);
    }

    BitmapFactory.Options imageOptions = new BitmapFactory.Options();
    imageOptions.inJustDecodeBounds = true;

    BitmapFactory.decodeFile(path, imageOptions);

    int initialWidth = imageOptions.outWidth;
    int initialHeight = imageOptions.outHeight;


    File resized = getResizedImage(path, initialWidth, initialHeight);
    if (resized == null) {
      image.putString("error", "Can't resize the image");
    } else {
      path = resized.getAbsolutePath();
    }
    image.putString("path", path);
    image.putString("originpath", path);

    if (!noData) {
      image.putString("data", getBase64StringFromFile(path));
    }

    putExtraFileInfo(path, image);
    return image;
  }

  Boolean checkFileSize(String filePath) {
    File file = new File(filePath);
    long length = file.length();
    length = length/1024;
    if(length > 5000) {
      return true;
    }
    else {
      return false;
    }
  }

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {

    response = Arguments.createMap();
    if (requestCode == CAMERA_RQ) {
      if (resultCode == RESULT_OK) {
        final File file = new File(data.getData().getPath());
        if(checkFileSize(file.getAbsolutePath())) {
          //compress this video
          runTranscodingPickVideo(file.getAbsolutePath());
          return;
        }
        response.putString("uri", "fileUri");
        response.putString("path", file.getAbsolutePath());
        mCallback.invoke(response);
        return;
        //
      } else if (data != null) {
        Exception e = (Exception) data.getSerializableExtra(MaterialCamera.ERROR_EXTRA);
        if (e != null) {
          e.printStackTrace();
          Toast.makeText(currentActivity, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
      }
    }
    // user cancel
    if (resultCode != RESULT_OK) {
      response.putBoolean("didCancel", true);
      mCallback.invoke(response);
      return;
    }


    Uri uri;
    switch (requestCode)
    {
      case REQUEST_LAUNCH_IMAGE_CAPTURE:
        uri = mCameraCaptureURI;
        break;
      case REQUEST_IMAGE_CROPPING:
        uri = mCropImagedUri;
        break;
      default:
        uri = data.getData();
    }
    if (requestCode != REQUEST_IMAGE_CROPPING && allowEditing == true) {
      Intent cropIntent = new Intent("com.android.camera.action.CROP");
      cropIntent.setDataAndType(uri, "image/*");
      cropIntent.putExtra("crop", "true");

      if (aspectX > 0 && aspectY > 0) {
        // aspectX:aspectY, the ratio of width to height
        cropIntent.putExtra("aspectX", aspectX);
        cropIntent.putExtra("aspectY", aspectY);
        cropIntent.putExtra("scale", true);
      }

      // we create a file to save the result
      File imageFile = createNewFile(true);
      mCropImagedUri = Uri.fromFile(imageFile);
      cropIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCropImagedUri);

      try {
        currentActivity.startActivityForResult(cropIntent, REQUEST_IMAGE_CROPPING);
      } catch(ActivityNotFoundException e) {
        e.printStackTrace();
      }
      return;
    }

    /////Uri uri;
    WritableArray multiImagesResult = new WritableNativeArray();
    switch (requestCode) {
      case REQUEST_IMAGE_CROPPING:
        uri = mCropImagedUri;
        multiImagesResult.pushMap(getImage(uri, true));
        response.putArray("multiImagesData", multiImagesResult);
        mCallback.invoke(response);
        break;
      case REQUEST_LAUNCH_IMAGE_CAPTURE:
        uri = mCameraCaptureURI;
        multiImagesResult.pushMap(getImage(uri, true));
        response.putArray("multiImagesData", multiImagesResult);
        mCallback.invoke(response);
        break;
      //multi images
      case 200:
        String[] all_path = data.getStringArrayExtra("all_path");
        for (String string : all_path) {
          /*WritableMap image = new WritableNativeMap();
          image.putString("imagePath", string);*/
          multiImagesResult.pushMap(getImageFromPath(string));
        }
        response.putArray("multiImagesData", multiImagesResult);
        mCallback.invoke(response);
        return;
      case REQUEST_LAUNCH_VIDEO_LIBRARY:
        if(checkFileSize(getRealPathFromURI(data.getData()))) {
          //compress this video
          runTranscoding(data);
          return;
        }
        response.putString("uri", data.getData().toString());
        response.putString("path", getRealPathFromURI(data.getData()));
        mCallback.invoke(response);
        return;
      case REQUEST_LAUNCH_VIDEO_CAPTURE:
        /*if(checkFileSize(getRealPathFromURI(data.getData()))) {
          //compress this video
          runTranscoding(data);
          return;
        }
        response.putString("uri", data.getData().toString());
        response.putString("path", getRealPathFromURI(data.getData()));
        mCallback.invoke(response);*/
        Toast.makeText(currentActivity,
                data.getStringExtra("videoPickPath"), Toast.LENGTH_SHORT).show();
        String videoPickPath = data.getStringExtra("videoPickPath");
        if(checkFileSize(videoPickPath)) {
          //compress this video
          runTranscodingPickVideo(videoPickPath);
          return;
        }
        response.putString("uri", "fileUri");
        response.putString("path", videoPickPath);
        mCallback.invoke(response);
        return;
      default:
        uri = null;
    }
  }

  private boolean permissionsCheck(Activity activity) {
    int writePermission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    int cameraPermission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA);
    if (writePermission != PackageManager.PERMISSION_GRANTED || cameraPermission != PackageManager.PERMISSION_GRANTED) {
      String[] PERMISSIONS = {
              Manifest.permission.WRITE_EXTERNAL_STORAGE,
              Manifest.permission.CAMERA
      };
      ActivityCompat.requestPermissions(activity, PERMISSIONS, 1);
      return false;
    }
    return true;
  }


  private boolean isCameraAvailable() {
    return mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
      || mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
  }

  private String getRealPathFromURI(Uri uri) {
    String result;
    String[] projection = {MediaStore.Images.Media.DATA};
    Cursor cursor = mReactContext.getContentResolver().query(uri, projection, null, null, null);
    if (cursor == null) { // Source is Dropbox or other similar local file path
      result = uri.getPath();
    } else {
      cursor.moveToFirst();
      int idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
      result = cursor.getString(idx);
      cursor.close();
    }
    return result;
  }

  private String getBase64StringFromFile(String absoluteFilePath) {
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream(new File(absoluteFilePath));
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }

    byte[] bytes;
    byte[] buffer = new byte[8192];
    int bytesRead;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        output.write(buffer, 0, bytesRead);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    bytes = output.toByteArray();
    return Base64.encodeToString(bytes, Base64.NO_WRAP);
  }

  /**
   * Create a resized image to fill the maxWidth/maxHeight values,the quality
   * value and the angle value
   *
   * @param realPath
   * @param initialWidth
   * @param initialHeight
   * @return resized file
   */
  private File getResizedImage(final String realPath, final int initialWidth, final int initialHeight) {
    Options options = new BitmapFactory.Options();
    options.inScaled = false;
    Bitmap photo = BitmapFactory.decodeFile(realPath, options);

    if (photo == null) {
        return null;
    }

    Bitmap scaledphoto = null;
    if (maxWidth == 0) {
      maxWidth = initialWidth;
    }
    if (maxHeight == 0) {
      maxHeight = initialHeight;
    }
    double widthRatio = (double) maxWidth / initialWidth;
    double heightRatio = (double) maxHeight / initialHeight;

    double ratio = (widthRatio < heightRatio)
            ? widthRatio
            : heightRatio;

    Matrix matrix = new Matrix();
    matrix.postRotate(angle);
    matrix.postScale((float) ratio, (float) ratio);

    ExifInterface exif;
    try {
      exif = new ExifInterface(realPath);

      int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);

      if (orientation == 6) {
        matrix.postRotate(90);
      } else if (orientation == 3) {
        matrix.postRotate(180);
      } else if (orientation == 8) {
        matrix.postRotate(270);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    scaledphoto = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    scaledphoto.compress(Bitmap.CompressFormat.JPEG, quality, bytes);

    File f = createNewFile(false);
    FileOutputStream fo;
    try {
      fo = new FileOutputStream(f);
      try {
        fo.write(bytes.toByteArray());
      } catch (IOException e) {
        e.printStackTrace();
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }

    // recycle to avoid java.lang.OutOfMemoryError
    if (photo != null) {
      scaledphoto.recycle();
      photo.recycle();
      scaledphoto = null;
      photo = null;
    }
    return f;
  }

  /**
   * Create a new file
   *
   * @return an empty file
   */
  private File createNewFile(final boolean forcePictureDirectory) {
    String filename = "image-" + UUID.randomUUID().toString() + ".jpg";
    if (tmpImage && forcePictureDirectory != true) {
      return new File(mReactContext.getCacheDir(), filename);
    } else {
      File path = Environment.getExternalStoragePublicDirectory(
              Environment.DIRECTORY_PICTURES);
      File f = new File(path, filename);

      try {
        path.mkdirs();
        f.createNewFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
      return f;
    }
  }

  private void putExtraFileInfo(final String path, WritableMap response) {
    // size && filename
    try {
      File f = new File(path);
      response.putDouble("fileSize", f.length());
      response.putString("fileName", f.getName());
    } catch (Exception e) {
      e.printStackTrace();
    }

    // type
    String extension = MimeTypeMap.getFileExtensionFromUrl(path);
    if (extension != null) {
      response.putString("type", MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
    }
  }

  private void parseOptions(final ReadableMap options) {
    noData = false;
    if (options.hasKey("noData")) {
      noData = options.getBoolean("noData");
    }
    maxWidth = 0;
    if (options.hasKey("maxWidth")) {
      maxWidth = options.getInt("maxWidth");
    }
    maxHeight = 0;
    if (options.hasKey("maxHeight")) {
      maxHeight = options.getInt("maxHeight");
    }
    aspectX = 0;
    if (options.hasKey("aspectX")) {
      aspectX = options.getInt("aspectX");
    }
    aspectY = 0;
    if (options.hasKey("aspectY")) {
      aspectY = options.getInt("aspectY");
    }
    quality = 100;
    if (options.hasKey("quality")) {
      quality = (int) (options.getDouble("quality") * 100);
    }
    tmpImage = true;
    if (options.hasKey("storageOptions")) {
      tmpImage = false;
    }
    allowEditing = false;
    if (options.hasKey("allowsEditing")) {
      allowEditing = options.getBoolean("allowsEditing");
    }
    forceAngle = false;
    angle = 0;
    if (options.hasKey("angle")) {
      forceAngle = true;
      angle = options.getInt("angle");
    }
    pickVideo = false;
    if (options.hasKey("mediaType") && options.getString("mediaType").equals("video")) {
      pickVideo = true;
    }
    videoQuality = 1;
    if (options.hasKey("videoQuality") && options.getString("videoQuality").equals("low")) {
      videoQuality = 0;
    }
    videoDurationLimit = 0;
    if (options.hasKey("durationLimit")) {
      videoDurationLimit = options.getInt("durationLimit");
    }
  }

  private static Pattern pattern = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2}).(\\d{3})");

  public static long dateParseRegExp(String period) {
    Matcher matcher = pattern.matcher(period);
    if (matcher.matches()) {
      return Long.parseLong(matcher.group(1)) * 3600000L
              + Long.parseLong(matcher.group(2)) * 60000
              + Long.parseLong(matcher.group(3)) * 1000
              + Long.parseLong(matcher.group(4));
    } else {
      throw new IllegalArgumentException("Invalid format " + period);
    }
  }

  private void addTextViewToLayout(String text) {

  }

  private void execFFmpegBinary(final String[] command) {
    try {
      ffmpeg.execute(command, new ExecuteBinaryResponseHandler() {
        @Override
        public void onFailure(String s) {
          addTextViewToLayout("FAILED with output : "+s);
        }

        @Override
        public void onSuccess(String s) {
          addTextViewToLayout("SUCCESS with output : "+s);
        }
        @Override
        public void onProgress(String s) {
          addTextViewToLayout("progress : "+s);
          if(s.contains("time="))
          {
            if(s.contains("bitrate="))
            {
              int timeIndex = s.indexOf("time=");
              int bitrateIndex = s.indexOf("bitrate=");
              String timeResult = s.substring(timeIndex + 5, bitrateIndex).replace(" ", "");
              while(timeResult.length() < 12)
              {
                timeResult += "0";
              }
              double dualTime = (double)dateParseRegExp(timeResult);
              double dProgress = dualTime/videoDuration;

              DecimalFormat df = new DecimalFormat("######0.00");
              dProgress = Double.valueOf(df.format(dProgress));
              if ((int)(dProgress*100) != 0 && (int)(dProgress*100) < 100) {
                progressBar.setProgress((int)(dProgress*100));
              }
            }
            else
            {
              //progressDialog.setMessage("wrongwrongwrongwrong\n"+s);
            }
          }
          else
          {
            //progressDialog.setMessage("处理中1\n"+s);
          }

        }

        @Override
        public void onStart() {

        }

        @Override
        public void onFinish() {
          progressBar.dismiss();
          Toast.makeText(currentActivity, "压缩成功", Toast.LENGTH_LONG).show();
          //response.putString("uri", imageReturnedIntent.getData().toString());
          response.putString("uri", "fileUri");
          response.putString("path", newFilePath);//
          mCallback.invoke(response);
        }
      });
    } catch (FFmpegCommandAlreadyRunningException e) {
      // do nothing for now
    }
  }

  public void runTranscodingPickVideo(final String pickVideoPath) {
    progressBar = new ProgressDialog(currentActivity);
    progressBar.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    progressBar.setTitle("视频压缩");
    progressBar.setMessage("待上传视频过大,正在压缩...");
    progressBar.setMax(100);
    progressBar.setProgress(0);
    progressBar.setCancelable(false);

    progressBar.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        ffmpeg.killRunningProcesses();
      }
    });
    progressBar.show();
    MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
    metaRetriever.setDataSource(pickVideoPath);
    Bitmap bmp = null;
    bmp = metaRetriever.getFrameAtTime();
    int videoHeight = bmp.getHeight();
    int videoWidth = bmp.getWidth();
    videoDuration = Long.valueOf(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
    //int width = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))/2;
    String deviceResolution = ("" + videoWidth) + "x" + ("" + videoHeight);
    final String newPath = pickVideoPath.split(".mp")[0] + "_compress.mp4";
    newFilePath = newPath;
    String[] complexCommand = {"-y", "-i", pickVideoPath,
            "-strict", "experimental", "-s", deviceResolution, "-r", "25", "-vcodec",
            "mpeg4", "-b", "900k", "-ab", "48000", "-ac", "2", "-ar", "22050", newPath};

    String[] command = complexCommand;
    execFFmpegBinary(command);
  }

  public void runTranscoding(final Intent imageReturnedIntent) {
    progressBar = new ProgressDialog(currentActivity);
    progressBar.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    progressBar.setTitle("视频压缩");
    progressBar.setMessage("待上传视频过大,正在压缩...");
    progressBar.setMax(100);
    progressBar.setProgress(0);
    progressBar.setCancelable(false);

    progressBar.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        ffmpeg.killRunningProcesses();
      }
    });
    progressBar.show();
    MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
    metaRetriever.setDataSource(getRealPathFromURI(imageReturnedIntent.getData()));
    Bitmap bmp = null;
    bmp = metaRetriever.getFrameAtTime();
    int videoHeight = bmp.getHeight();
    int videoWidth = bmp.getWidth();
    videoDuration = Long.valueOf(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
    //int width = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))/2;
    String deviceResolution = ("" + videoWidth) + "x" + ("" + videoHeight);
    final String newPath = getRealPathFromURI(imageReturnedIntent.getData()).split(".mp")[0] + "_compress.mp4";
    newFilePath = newPath;
    String[] complexCommand = {"-y", "-i", getRealPathFromURI(imageReturnedIntent.getData()),
            "-strict", "experimental", "-s", deviceResolution, "-r", "25", "-vcodec",
            "mpeg4", "-b", "900k", "-ab", "48000", "-ac", "2", "-ar", "22050", newPath};

    String[] command = complexCommand;
    execFFmpegBinary(command);
  }

  public void onNewIntent(Intent intent) { }
}
