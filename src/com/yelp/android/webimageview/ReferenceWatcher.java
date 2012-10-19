/* Copyright (c) 2012 Yelp Inc
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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ReferenceWatcher<T> {

	private final ReferenceQueue<? super T> mQueue;
	private HashSet<Reference<T>> mRefs;
	private final int mThreshold;

	private final AtomicInteger mCount;


	public ReferenceWatcher() {
		this(20);
	}

	public ReferenceWatcher(int threshold) {
		mQueue = new ReferenceQueue<T>();
		mRefs = new HashSet<Reference<T>>();
		mThreshold = threshold;
		mCount = new AtomicInteger();
	}

	public void watch(T ref) {
		mRefs.add(new SoftReference<T>(ref, mQueue));
		if (mCount.incrementAndGet() >= mThreshold) {
			clean();
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private final void clean() {
		Reference ref = null;
		while((ref = mQueue.poll()) != null) {
			T value = (T)ref.get();
			if (value != null) {
				mRefs.remove(value);
			}
		}
	}

	public Set<T> getSnapShotAndClean() {
		clean();
		HashSet<T> values = new HashSet<T>();
		for (Reference<T> tempRef : mRefs) {
			T value = tempRef.get();
			if(value != null) {
				values.add(value);
			}
		}
		mCount.set(mRefs.size());

		return values;
	}
}
