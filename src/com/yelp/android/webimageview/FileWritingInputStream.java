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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A wrapper around an InputStream which writes bytes to the provided File as
 * they are read from the stream. Calling close on this stream closes both the
 * file stream and the provided InputStream.
 *
 * @author pretz
 *
 */
public class FileWritingInputStream extends FilterInputStream {

	private static final int BUFFER_SIZE = 4096;
	final OutputStream mOutput;
	final FileDescriptor mFd;

	public FileWritingInputStream(InputStream stream, FileOutputStream file) throws FileNotFoundException {
		super(new BufferedInputStream(stream, BUFFER_SIZE));
		try {
			mFd = file.getFD();
		} catch (IOException e) {
			throw new FileNotFoundException("Could not get file descriptor for given file.");
		}
		mOutput = new BufferedOutputStream(file, BUFFER_SIZE);
	}

	@Override
	public int read(byte[] buffer, int offset, int count) throws IOException {
		int read = super.read(buffer, offset, count);
		if (read >= 0) {
			mOutput.write(buffer, offset, read);
		}
		return read;
	}

	@Override
	public int read(byte[] buffer) throws IOException {
		return this.read(buffer, 0, buffer.length);
	}

	@Override
	public int read() throws IOException {
		int read = super.read();
		if (read >= 0) {
			mOutput.write(read);
		}
		return read;
	}

	@Override
	public void close() throws IOException {
		super.close();
		mOutput.flush();
		mFd.sync();
		mOutput.close();
	}

	@Override
	public boolean markSupported() {
		// Rewinding writing to a file is too complicated.  Just don't support it.
		return false;
	}

	@Override
	public void mark(int readlimit) {
		// Pass, prevent calling through to filtered stream
	}

	@Override
	public synchronized void reset() throws IOException {
		throw new IOException("Reset not supported by " + this.getClass().getSimpleName());
	}

}
