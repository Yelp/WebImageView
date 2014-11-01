/* Copyright (c) 2009 Matthias Käppler, Yelp Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yelp.android.webimageview;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.yelp.common.collect.MapMaker;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 * A simple 2-level cache for bitmap images consisting of a small and fast
 * in-memory cache (1st level cache) and a slower but bigger disk cache (2nd
 * level cache). For second level caching, the application's cache directory
 * will be used. Please note that Android may at any point decide to wipe that
 * directory.
 * </p>
 * <p>
 * When pulling from the cache, it will first attempt to load the image from
 * memory. If that fails, it will try to load it from disk. If that succeeds,
 * the image will be put in the 1st level cache and returned. Otherwise it's a
 * cache miss, and the caller is responsible for loading the image from
 * elsewhere (probably the Internet).
 * </p>
 * <p>
 * Pushes to the cache are always write-through (i.e., the image will be stored
 * both on disk and in memory). Fetches can only go to memory or go to memory
 * and on-disk cache.
 * </p>
 *
 * @author Matthias Kaeppler, modified by Alex Pretzlav
 */
public class ImageCache {

	private static final String TAG = "DroidFu.ImageCache";

	private static final AtomicInteger COUNTER = new AtomicInteger();

	private static final int MEGABYTE_IN_BYTES = 1024 * 1024;
	private static final int MAX_INTERNAL = MEGABYTE_IN_BYTES;
	private static final int MAX_EXTERNAL = MEGABYTE_IN_BYTES * 5;

	/** trimCache() will be called every time this many new files are created on disk */
	private static final int CACHE_CLEAR_FREQUENCY = 75;

	private static final OptionsFactory OPTIONS = Integer.valueOf(VERSION.SDK) >= Build.VERSION_CODES.DONUT ? new EfficientOptionsFactory() : new OptionsFactory();

	/**
	 * Directory where cached images will be stored. This object is also used as
	 * the lock around the cache directory.
	 */
	/* package */ File mSecondLevelCacheDir;

	final File mInternalCacheDir;
	File mExternalCacheDir;

	Context mContext;

	/* package */ final File mPermanentCacheDir;

	private final ConcurrentMap<String, Bitmap> mCache;

	private int mInMemoryCacheMissCount;

	private BroadcastReceiver mExternalStorageReceiver;

	public ImageCache(Context context, int initialCapacity, int concurrencyLevel) {
		mContext = context;
		this.mCache = new MapMaker().initialCapacity(initialCapacity).concurrencyLevel(
			concurrencyLevel).softValues().makeMap();
		this.mPermanentCacheDir = new File(context.getApplicationContext().getCacheDir()
				.getAbsolutePath() + "/permanent_images");
		this.mInternalCacheDir = new File(context.getApplicationContext().getCacheDir()
				+ "/droidfu/imagecache");
		updateExternalStorageState(context);
		registerForExternalStorageUpdates(context);
	}

	/**
	 * http://developer.android.com/reference/android/os/Environment.html#getExternalStorageDirectory()
	 */
	void registerForExternalStorageUpdates(Context context) {
		mExternalStorageReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				updateExternalStorageState(context);
			}
		};
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
		filter.addAction(Intent.ACTION_MEDIA_REMOVED);
		filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
		filter.addDataScheme("file");
		// TODO: Can this cause the app to leak ImageCaches if there are multiple created?
		context.registerReceiver(mExternalStorageReceiver, filter);
	}

	void updateExternalStorageState(Context context) {
		if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
			if (mExternalCacheDir == null) {
				File externalCacheDir = getExternalCacheDir(context);
				if (externalCacheDir != null) {
					mExternalCacheDir = new File(externalCacheDir, "images");
				} else {
					useInternalCacheDir();
					return;
				}
			}
			this.mExternalCacheDir.mkdirs();
			if (!this.mExternalCacheDir.exists()) {
				useInternalCacheDir();
				return;
			}

			// If less than our max cache size is available on SD, use internal cache
			if (!checkExternalFreeSpace() || !assertExternalMountWritable()) {
				useInternalCacheDir();
			} else {
				this.mSecondLevelCacheDir = this.mExternalCacheDir;
				// Clear internal dir
				clearDirectory(mInternalCacheDir, Long.MAX_VALUE);
			}
		} else {
			useInternalCacheDir();
		}
	}

	// Because sometimes Android seems to lie :(
	boolean assertExternalMountWritable() {
		File temp = new File(this.mExternalCacheDir, "cache.dir" + SystemClock.elapsedRealtime());
		boolean safe = false;
		try {
			safe = temp.createNewFile();
			safe &= temp.delete();
		} catch(IOException e) {
			safe = false;
		}
		return safe;
	}
	boolean checkExternalFreeSpace() {
		StatFs stats = new StatFs(this.mExternalCacheDir.getAbsolutePath());
		return ((long)stats.getAvailableBlocks() * (long)stats.getBlockSize()) > MAX_EXTERNAL;
	}

	void useInternalCacheDir() {
		this.mSecondLevelCacheDir = this.mInternalCacheDir;
		this.mSecondLevelCacheDir.mkdirs();
	}

	final boolean isUsingExternalCache() {
		return mSecondLevelCacheDir == mExternalCacheDir;
	}

	/**
	 * Checks only the in-memory cache for the requested key. Use getFile() to
	 * check the on-disk cache.
	 */
	public Bitmap get(Object key) {
		String imageUrl = String.valueOf(key);
		Bitmap bitmap = mCache.get(imageUrl);

		if (bitmap != null) {
			// 1st level cache hit (memory)
			return bitmap;
		}
		// cache miss
		return null;
	}

	/**
	 * Double-checks the in-memory cache and if not available decodes
	 * the on-disk cached image instead if available and inserts
	 * it into the in-memory cache.
	 * @param key The URL of the image to fetch from cache.
	 * @return
	 */
	public Bitmap getBitmap(Object key) {
		String imageUrl = String.valueOf(key);
		// Double-check cache before breaking down and reading flash memory
		Bitmap bitmap = get(imageUrl);
		if (bitmap == null) {
			synchronized(this.mSecondLevelCacheDir) {
				File imageFile = getImageFile(this.mSecondLevelCacheDir, imageUrl);
				if (!imageFile.exists()) {
					imageFile = getImageFile(this.mPermanentCacheDir, imageUrl);
				}
				if (imageFile.exists()) {
					// 2nd level cache hit (disk)

					bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), OPTIONS.getOptions());
					if (bitmap == null) {
						// treat decoding errors as a cache miss
						return null;
					}
					// Set file modified date to now to attempt to keep on disk longer
					imageFile.setLastModified(System.currentTimeMillis());
					if (BuildConfig.DEBUG) {
						mInMemoryCacheMissCount++;
						Log.i("ImageCache", "In-memory cache miss #" + mInMemoryCacheMissCount);
					}
					mCache.put(imageUrl, bitmap);
				}
			}
		}
		return bitmap;
	}

	/**
	 * Writes the provided image data to on-disk cache and simultaneously
	 * decodes it to a bitmap and stores it in the in-memory cache.
	 *
	 * The original Droid-Fu implementaion of this method took a Bitmap object
	 * which was re-compressed to a jpeg in the on-disk cache. This version
	 * saves the image as sent by the server and thus doesn't cause any
	 * compression or reformatting issues.
	 *
	 * @param imageUrl
	 *            The URL for the image to use for caching.
	 * @param data
	 * @return The decoded Bitmap built from data or null if data is invalid.
	 * @throws IOException
	 */
	public Bitmap put(String imageUrl, InputStream data) throws IOException {
		return put(imageUrl, data, false);
	}

	/**
	 * Writes the provided image data to on-disk cache and simultaneously
	 * decodes it to a bitmap and stores it in the in-memory cache.
	 *
	 * The original Droid-Fu implementaion of this method took a Bitmap object
	 * which was re-compressed to a jpeg in the on-disk cache. This version
	 * saves the image as sent by the server and thus doesn't cause any
	 * compression or reformatting issues.
	 *
	 * @param imageUrl
	 *            The URL for the image to use for caching.
	 * @param data
	 *            A stream to read the bitmap information from
	 * @param cachePermanently
	 *            If true, the image will be stored permanently in the
	 *            Application data instead of in the cache directory.
	 * @return
	 * @throws IOException
	 */
	public Bitmap put(String imageUrl, InputStream data, boolean cachePermanently) throws IOException {
		incrementAndTrim();
		File imageFile;
		// NOTE: Android can clear the cache at any time, so we need to make sure our directories
		// exist every time we write to them.
		if (cachePermanently) {
			this.mPermanentCacheDir.mkdirs();
			imageFile = getImageFile(this.mPermanentCacheDir, imageUrl);
		} else {
			this.mSecondLevelCacheDir.mkdirs();
			imageFile = getImageFile(this.mSecondLevelCacheDir, imageUrl);
		}
		FileWritingInputStream stream = null;
		try {
			stream = new FileWritingInputStream(data, new FileOutputStream(imageFile));
		} catch (FileNotFoundException e) {
			// SD card may have been unmounted and is now inaccessible
			if (isUsingExternalCache()) {
				updateExternalStorageState(mContext);
				if (!isUsingExternalCache()) {
					return put(imageUrl, data, cachePermanently);
				} else {
					throw e;
				}
			} else {
				throw e;
			}
		}
		Bitmap image = null;
		try {
			image = BitmapFactory.decodeStream(stream, null, OPTIONS.getOptions());
		} finally {
			stream.close();
		}
		if (image == null) { // Delete potentially corrupt partial file
			imageFile.delete();
			// We might be out of external storage space, fail over to internal and retry
			if (isUsingExternalCache()) {
				updateExternalStorageState(mContext);
				if (!isUsingExternalCache()) {
					return put(imageUrl, data, cachePermanently);
				}
			}
		} else {
			mCache.put(imageUrl, image);
		}
		return image;
	}

	private void incrementAndTrim() {
		if (COUNTER.incrementAndGet() >= CACHE_CLEAR_FREQUENCY) {
			trimCache();
			COUNTER.set(0);
		}
	}

	public void clear() {
		mCache.clear();
	}

	File getImageFile(File directory, String imageUrl) {
		String fileName = Integer.toHexString(imageUrl.hashCode());
		return new File(directory, fileName);
	}

	/**
	 * Trim the disc cache from droid fu to be of the size we want.
	 * Oldest files should be deleted first.
	 *
	 * @author greg
	 */
	public void trimCache() {
		File dir = mSecondLevelCacheDir;
		synchronized(mSecondLevelCacheDir) {
			if (dir.exists() && dir.isDirectory()) {
				File[] files = dir.listFiles();
				if (files != null) {
					try {
						// Have the newest files first
						Arrays.sort(files, FILE_COMPARATOR);
						Log.i(TAG, "Sorting by oldest last");
					} catch(RuntimeException e) {
						try {
							Arrays.sort(files, FILE_COMPARATOR_FALLBACK);
							Log.w(TAG, "Purging files by file name comparision rather than age");
						} catch(RuntimeException e1) {
							Log.w(TAG, "Tried to purge cache in a smart order, but failed ... going to purge randomly");
						}
					}
					long sizeOnDisk = 0;
					long longSizeOneDay = System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS;
					int numberOfFiles = 0;
					for (File file : files) {
						int maxSize = this.isUsingExternalCache() ? MAX_EXTERNAL : MAX_INTERNAL;
						if (sizeOnDisk < maxSize && longSizeOneDay > file.lastModified()) {
							// Count until MAX
							sizeOnDisk += file.length();
						} else {
							// Then the rest of the files are old
							// and we want to delete them
							numberOfFiles++;
							// Should be safe since it's the lowest level cache
							if (!file.delete()) {
								// Not guaranteed to do anything, but it's an attempt
								// android makes it hard to define, onExit();
								file.deleteOnExit();
							}
						}
					}
					Log.d(TAG, String.format("Purged %d files and left with %d bytes on disk", numberOfFiles, sizeOnDisk));
				}
			}
			// Trim permanent cache for any file older than a week
			long oneWeekAgo = System.currentTimeMillis() - (DateUtils.DAY_IN_MILLIS * 7);
			clearDirectory(mPermanentCacheDir, oneWeekAgo);
		}
	}

	/**
	 * Deletes all files in the provided directory that were last modified
	 * before the date given in milliseconds since epoch.
	 *
	 * @param directory
	 * @param olderThanDate
	 */
	static void clearDirectory(File directory, long olderThanDate) {
		File[] files = directory.listFiles();
		if (files != null && files.length > 0) {
			for (File file : files) {
				if (file.lastModified() < olderThanDate) {
					if (!file.delete()) {
						file.deleteOnExit();
					}
				}
			}
		}
	}

	/**
	 * Returns a file pointing to /<sdcard>/Android/data/<app package name>/cache
	 * This is equivalent to the default implementaion of
	 * Context.getExternalCacheDir() in Froyo. Does not make the directory
	 * if it does not exist.
	 * @param context Used to resolve the Application package name.
	 * @return
	 */
	public static File getExternalCacheDir(Context context) {
		try {
			return ExternalStorageWrapper.getExternalCacheDir(context);
		} catch (Throwable t) {
			return new File(TextUtils.join(File.separator, new String[] {
					Environment.getExternalStorageDirectory().getAbsolutePath(), "Android", "data",
					context.getPackageName(), "cache"}));
		}
	}

	/**
	 * Class to wrap calls to Context.getExternalCacheDir while catching
	 * verifier errors.
	 * @see http://developer.android.com/resources/articles/backward-compatibility.html
	 * @author pretz
	 *
	 */
	@TargetApi(8)
	public static class ExternalStorageWrapper {

		public static File getExternalCacheDir(Context context) {
			return context.getExternalCacheDir();
		}
	}

	private static final Comparator<File> FILE_COMPARATOR = new Comparator<File>() {
		@Override
		public int compare(File object1, File object2) {
			int diff = (int) (object2.lastModified() - object1.lastModified());
			return diff != 0 ? diff : object2.compareTo(object1);
		}
	};

	private static final Comparator<File> FILE_COMPARATOR_FALLBACK = new Comparator<File>() {
		@Override
		public int compare(File object1, File object2) {
			return object2.getName().compareTo(object2.getName());
		}
	};

	private static class OptionsFactory {
		public BitmapFactory.Options getOptions() {
			return new BitmapFactory.Options();
		}
	}

	private static class EfficientOptionsFactory extends OptionsFactory {

		@Override
		public Options getOptions() {
			Options options = super.getOptions();
			options.inInputShareable = true;
			options.inPurgeable = true;
			return options;
		}
	}
}
