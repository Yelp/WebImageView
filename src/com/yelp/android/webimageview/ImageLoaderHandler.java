/* Copyright (c) 2009 Matthias Käppler
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

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import java.lang.ref.WeakReference;

/**
 * An ImageLoaderHandler both handles the receiving of an image and acts as a request for image
 * download. Instances of this class can be passed to the ImageLoader and are notified when the
 * image loading has been completed. ImageLoaderHandler instances with higher priority (lower
 * absolute value), are downloaded before others.
 *
 * @author Matthias Käppler, Greg Giacovelli
 */
public class ImageLoaderHandler<ImageView> extends Handler {

    private final WeakReference<ImageView> mWeakImageView;
    protected long priority;

    public ImageLoaderHandler(ImageView imageView) {
        mWeakImageView = new WeakReference<ImageView>(imageView);
        priority = 0;
    }


    @Override
    public void handleMessage(Message msg) {
        if (msg.what == ImageLoader.HANDLER_MESSAGE_ID) {
            Bundle data = msg.getData();
            Bitmap bitmap = data.getParcelable(ImageLoader.BITMAP_EXTRA);
            if (mWeakImageView.get() != null) {
                ((WebImageView)mWeakImageView.get()).setImageBitmap(bitmap);
            }
        }
    }

    ImageView getImageView() {
        return mWeakImageView.get();
    }

}
