/*
 * Copyright (c) 2011, Polidea
 * Copyright © 2013–2016 Michael von Glasow.
 * 
 * This file is part of LSRN Tools.
 *
 * LSRN Tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSRN Tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSRN Tools.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vonglasow.michael.satstat.utils;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A task which retrieves the contents of a remote directory in the background and notifies a listener
 * upon completion.
 */
public class RemoteDirListTask {
private static final String TAG = RemoteDirListTask.class.getSimpleName();
private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);

private RemoteDirListListener listener = null;
private RemoteFile parent = null;
private final AtomicBoolean cancelled = new AtomicBoolean(false);
private Future<?> future = null;

/**
 * Creates a new {@code RemoteDirListTask} task, and registers it with a listener.
 * 
 * @param listener The {@link RemoteDirListListener} which will be notified when the task has completed.
 * @param parent The directory to be listed. When this task finishes, it populates the {@code children}
 * member of {@code parent} with the objects it retrieved. May be {@code null}.
 */
public RemoteDirListTask(RemoteDirListListener listener, RemoteFile parent) {
this.listener = listener;
this.parent = parent;
}

/**
 * Executes the task with the given URL.
 */
public void execute(String url) {
	if (cancelled.get()) return;
	final RemoteFile p = parent;
	future = sExecutor.submit(() -> {
		if (cancelled.get()) return;
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		RemoteFile[] result = listRemote(url);

		mainHandler.post(() -> {
			if (!cancelled.get()) {
				if (p != null)
					p.children = result;
				if (listener != null)
					listener.onRemoteDirListReady(RemoteDirListTask.this, result);
			}
		});
	});
}

/**
 * Cancels the task.
 */
public void cancel(boolean mayInterruptIfRunning) {
	cancelled.set(true);
	if (future != null && !future.isDone()) {
		future.cancel(mayInterruptIfRunning);
	}
}

/**
 * Returns true if the task has been cancelled.
 */
public boolean isCancelled() {
	return cancelled.get();
}

private RemoteFile[] listRemote(String params) {
Uri uri = Uri.parse(params);
RemoteFile[] rfiles = null;
if (uri.getScheme() == null)
return null;
if (uri.getScheme().equals("http") || uri.getScheme().equals("https"))
rfiles = HttpDownloader.list(params);

if (rfiles == null)
Log.w(TAG, "Error – could not retrieve content!");
else if (rfiles.length == 0)
Log.w(TAG, "Remote directory is empty.");

return rfiles;
}
}
