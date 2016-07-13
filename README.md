# DEPRECATED

WebImageView is deprecated and no further development will be taking place. Existing versions will continue
to function, but we recommend that you check out alternative libraries.

WebImageView
============

WebImageView is an improved, enhanced, and sometimes simplified version of Droid-Fu's [WebImageView](https://github.com/kaeppler/droid-fu/blob/master/src/main/java/com/github/droidfu/widgets/WebImageView.java).

Some notable differences:

* It inherits directly from ImageView
* It caches both on memory and on disk, supporting the native external cache directory when available
* It caches the exact image file downloaded from the server, avoiding ugly and expensive re-compression
* It's quite fast


Example
-------

### Make sure to initialize the loader and cache in your Application subclass' onCreate:

``` java
public void onCreate() {
	super.onCreate();
	ImageLoader.initialize(this, null, BuildConfig.DEBUG));
}
```

### Use it in an XML layout

``` xml
<?xml version="1.0" encoding="utf-8"?>
<merge
	xmlns:android="http://schemas.android.com/apk/res/android"
	xmlns:yelp="http://schemas.android.com/apk/res/com.yelp.android">
	<com.yelp.android.webimageview.WebImageView
		android:id="@+id/user_image"
		android:layout_height="100dp"
		android:layout_width="100dp"
		yelp:imageUrl="http://yelp.com/photo/VdqOyPqzoRKWZuGW3HK6DA/ms.jpg" />
</merge>
```

### Use it in an Activity

``` java
public void onCreate(Bundle icicle) {
	super.onCreate(icicle);
	setContentView(R.layout.my_awesome_activity);

	WebImageView userImage = (WebImageView)getViewById(R.id.user_image);
	userImage.setImageUrl("http://yelp.com/photo/VdqOyPqzoRKWZuGW3HK6DA/ms.jpg");
}
```

That's it!
----------

WebImageView does all its downloading and cache-from-disk loading in its own dedicated thread pool, so you never need to worry about it affecting scroll performance or otherwise degrading the experience of using your app.

License
-------

WebImageView is licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.html)

