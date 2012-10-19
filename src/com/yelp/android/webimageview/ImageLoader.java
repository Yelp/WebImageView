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

import com.yelp.android.webimageview.WebImageView.WebImageLoaderHandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Realizes an background image loader backed by a two-level cache. If the image
 * to be loaded is present in the cache, it is set immediately on the given
 * view. Otherwise, a thread from a thread pool will be used to download the
 * image in the background and set the image on the view as soon as it
 * completes.
 *
 * @author Matthias Kaeppler, modified by Alex Pretzlav
 */
public class ImageLoader implements Runnable {

	private static PausableThreadPoolExecutor executor;

	public static ImageCache imageCache;

	private static final int DEFAULT_POOL_SIZE = 2;

	public static final int HANDLER_MESSAGE_ID = 0;

	public static final String BITMAP_EXTRA = "droidfu:extra_bitmap";

	private static int numAttempts = 3;

	private static ReferenceWatcher<ImageLoader> REQUESTS;
	/**
	 * @param numThreads
	 *        the maximum number of threads that will be started to download
	 *        images in parallel
	 */
	public static void setThreadPoolSize(int numThreads) {
		executor.setMaximumPoolSize(numThreads);
	}

	/**
	 * @param numAttempts
	 *        how often the image loader should retry the image download if
	 *        network connection fails
	 */
	public static void setMaxDownloadAttempts(int numAttempts) {
		ImageLoader.numAttempts = numAttempts;
	}

	/**
	 * This method must be called before any other method is invoked on this
	 * class. Please note that when using ImageLoader as part of
	 * {@link WebImageView} or {@link WebGalleryAdapter}, then there is no need
	 * to call this method, since those classes will already do that for you.
	 * This method is idempotent: You may call it multiple times without any
	 * side effects.
	 *
	 * @param context
	 *        the current context
	 */
	public static synchronized void initialize(final Context context, final UncaughtExceptionHandler exceptionHandler) {
		if (executor == null) {
			REQUESTS = new ReferenceWatcher<ImageLoader>();
			final int MAX_IMAGE_REQUEST_SIZE = 100;
			final PriorityBlockingQueue<? extends Runnable> queue = new BoundPriorityBlockingQueue<ImageLoader>(MAX_IMAGE_REQUEST_SIZE, DEFAULT_POOL_SIZE * 12, COMPARE);

			executor = new PausableThreadPoolExecutor (DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE, 300, TimeUnit.MILLISECONDS, queue);
			executor.setThreadFactory(new ThreadFactory() {
				private final AtomicInteger COUNTER = new AtomicInteger();
				@Override
				public Thread newThread(final Runnable r) {
					Runnable priorityRunnable = new Runnable() {
						@Override
						public void run() {
							android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
							r.run();
						}
					};
					Thread thread = new Thread(priorityRunnable);
					thread.setDaemon(true);
					thread.setName("ImageLoader-" + COUNTER.incrementAndGet());
					if (exceptionHandler != null) {
						thread.setUncaughtExceptionHandler(exceptionHandler);
					}
					return thread;
				}
			});

		}
		if (imageCache == null) {
			imageCache = new ImageCache(context, 25, DEFAULT_POOL_SIZE);
		}
		context.registerReceiver(RECEIVER, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	public static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (!TextUtils.equals(intent.getAction(), ConnectivityManager.CONNECTIVITY_ACTION)) {
				return;
			}
			if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
				executor.pause();
			} else {
				executor.resume();
			}
		}
	};

	/**
	 * Enqueues the requested imageUrl to be downloaded to the cache so it will
	 * be ready to be viewed.
	 *
	 * @param imageUrl
	 */
	public static void preload(String imageUrl) {
		if (!TextUtils.isEmpty(imageUrl) && imageCache.get(imageUrl) == null) {
			executor.execute(new ImageLoader(imageUrl));
		}
	}

	/**
	 * Triggers the image loader for the given image and view. The image loading
	 * will be performed concurrently to the UI main thread, using a fixed size
	 * thread pool. The loaded image will be posted back to the given ImageView
	 * upon completion.
	 *
	 * @param imageUrl
	 *        the URL of the image to download
	 * @param imageView
	 *        the ImageView which should be updated with the new image
	 * @param savePermanently
	 *            If true, the provided image will be saved permanently outside
	 *            of the cache directory
	 */
	public static void start(String imageUrl, ImageView imageView, boolean savePermenently) {
		ImageLoader loader = new ImageLoader(imageUrl, imageView, savePermenently);
		synchronized (imageCache) {
			Bitmap image = imageCache.get(imageUrl);
			if (image == null) {
				// fetch the image in the background
				executor.execute(loader);
			} else if (imageView instanceof WebImageView) {
				((WebImageView)imageView).setImageBitmap(image, true);
			} else {
				imageView.setImageBitmap(image);
			}
		}
	}

	/**
	 * Triggers the image loader for the given image and handler. The image
	 * loading will be performed concurrently to the UI main thread, using a
	 * fixed size thread pool. The loaded image will not be automatically posted
	 * to an ImageView; instead, you can pass a custom
	 * {@link ImageLoaderHandler} and handle the loaded image yourself (e.g.
	 * cache it for later use).
	 *
	 * @param imageUrl
	 *            the URL of the image to download
	 * @param handler
	 *            the handler which is used to handle the downloaded image
	 * @param savePermanently
	 *            If true, the provided image will be saved permanently outside
	 *            of the cache directory
	 */
	public static void start(String imageUrl, ImageLoaderHandler handler, boolean savePermanently) {
		ImageLoader loader = new ImageLoader(imageUrl, handler, savePermanently);
		loader.mPriority = handler.priority;
		Bitmap image = imageCache.get(imageUrl);
		if (image == null) {
			// fetch the image in the background
			executor.execute(loader);
		} else if (handler instanceof WebImageLoaderHandler) {
			WebImageView view = ((WebImageLoaderHandler)handler).mView.get();
			if (view != null) {
				view.setImageBitmap(image, true);
				loader.notifyImageLoaded(image);
			}
		} else {
			loader.notifyImageLoaded(image);
		}
	}

	public static final Set<ImageLoader> getSnapShot() {
		return REQUESTS.getSnapShotAndClean();
	}

	/**
	 * Clears the 1st-level cache (in-memory cache). A good candidate for
	 * calling in {@link android.app.Application#onLowMemory()}.
	 * Also trims the second level (on-disk) cache.
	 */
	public static void clearCache() {
		imageCache.clear();
		imageCache.trimCache();
	}

	/**
	 * Trims the second level (on-disk) cache so we don't use too much
	 * space on the poor little Android device.
	 */
	public static void trimCache() {
		if (imageCache != null) {
			imageCache.trimCache();
		}
	}

	private static final Comparator<ImageLoader> COMPARE = new Comparator<ImageLoader>() {
		@Override
		public int compare(ImageLoader object1, ImageLoader object2) {
			return (int) (object1.mPriority - object2.mPriority);
		};
	};

	public final String imageUrl;
	private Handler handler;
	public final boolean cachePermanently;
	private long mPriority;
	private int mResponse;

	ImageLoader(String imageUrl) {
		this.imageUrl = imageUrl;
		this.cachePermanently = false;
	}

	private ImageLoader(String imageUrl, ImageView imageView, boolean cachePermanently) {
		this(imageUrl, new ImageLoaderHandler(imageView), cachePermanently);
	}

	private ImageLoader(String imageUrl, ImageLoaderHandler handler, boolean cachePermanently) {
		this.imageUrl = imageUrl;
		this.handler = handler;
		this.cachePermanently = cachePermanently;
	}

	public int getResponse() {
		return mResponse;
	}

	public static File getImageFile(String path) {
		return imageCache.getImageFile(imageCache.mSecondLevelCacheDir, path);
	}

	@Override
	public void run() {
		REQUESTS.watch(this);
		Bitmap bitmap = null;
		int timesTried = 1;
		// Check file-based cache on background thread
		bitmap = imageCache.getBitmap(this.imageUrl);
		if (bitmap == null) {
			while (timesTried <= numAttempts) {
				InputStream connectionStream = null;
				try {
					URLConnection connection = new URL(imageUrl).openConnection();
					if (connection instanceof HttpURLConnection &&
							(mResponse = ((HttpURLConnection)connection).getResponseCode()) > 300) {
						return; // Otherwise it'll throw an IOException and this thread will get stuck retrying a bad URL
					}
					connectionStream = connection.getInputStream();
					if (connectionStream == null) {
						return; // Nothing to be done ....
					}
					bitmap = imageCache.put(imageUrl, connectionStream, this.cachePermanently);
					break;
				} catch (IOException e) {
					Log.w(ImageLoader.class.getSimpleName(), "download for " + imageUrl
							+ " failed (attempt " + timesTried + ")");
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e1) {
						// Oh well, pass
					}
					timesTried++;
				} finally {
					if (connectionStream != null) {
						try {
							connectionStream.close();
						} catch (IOException e) {
							// Nothing to be done
						}
					}
				}
			}
		}

		if (bitmap != null && this.handler != null) {
			notifyImageLoaded(bitmap);
		}
	}

	public void notifyImageLoaded(Bitmap bitmap) {
		Message message = new Message();
		message.what = HANDLER_MESSAGE_ID;
		Bundle data = new Bundle();
		data.putParcelable(BITMAP_EXTRA, bitmap);
		message.setData(data);
		handler.sendMessage(message);
	}


	/**
	 * A Pausable Threadpool Executor taken from the javadocs for ThreadPoolExecutor.
	 * We can use this to turn on/off processing of image tasks without destroying the threadpool
	 * @author greg
	 * @see http://download.oracle.com/javase/1.5.0/docs/api/java/util/concurrent/ThreadPoolExecutor.html
	 *
	 */
	public static class PausableThreadPoolExecutor extends ThreadPoolExecutor {
		private final ReentrantLock mLock;
		private final Condition mPauseCondition;
		private boolean isPaused;

		@SuppressWarnings("unchecked")
		public PausableThreadPoolExecutor(int corePoolSize,
				int maximumPoolSize, long keepAliveTime, TimeUnit unit,
				PriorityBlockingQueue<? extends Runnable> workQueue) {
			super(corePoolSize, maximumPoolSize, keepAliveTime, unit, (BlockingQueue<Runnable>)workQueue);
			mLock = new ReentrantLock();
			mPauseCondition = mLock.newCondition();
		}


		public void pause() {
			mLock.lock();
			try {
				isPaused = true;
			} finally {
				mLock.unlock();
			}
		}

		public void resume() {
			mLock.lock();
			try {
				isPaused = false;
				mPauseCondition.signalAll();
			} finally {
				mLock.unlock();
			}
		}
		@Override
		protected void beforeExecute(Thread t, Runnable r) {
			super.beforeExecute(t, r);
			mLock.lock();
			try {
				while(isPaused) {
					try {
						mPauseCondition.await();
					} catch (InterruptedException e) {
						// Share the love
						t.interrupt();
					}
				}
			} finally {
				mLock.unlock();
			}
		}


	}

	/**
	 * A PriorityBlockingQueue with a max length. When this length is reached, the contents are drained
	 * to half its size to make sure we don't grow out of control. Lower priority tasks will be removed.
	 * @author greg
	 *
	 * @param <T> The type of the contents this will hold
	 */
	@SuppressWarnings("serial")
	private static final class BoundPriorityBlockingQueue<T> extends PriorityBlockingQueue<T> {
		private final int mMaxSize;

		public BoundPriorityBlockingQueue(int maxSize, int initialCapacity, Comparator<? super T> comparator) {
			super(initialCapacity, comparator);
			mMaxSize = maxSize;
		}

		/**
		 * Overriden to allow for size check on insert
		 */
		@Override
		public boolean offer(T e) {
			boolean rtVal = super.offer(e);
			if (size() > mMaxSize) {
				LinkedList<T> copy = new LinkedList<T>();
				// Block and take all next in line to be executed
				drainTo(copy, mMaxSize / 2);
				// Clear the rest
				super.clear();
				// continue
				super.addAll(copy);
			}
			return rtVal;
		};

	}
}
