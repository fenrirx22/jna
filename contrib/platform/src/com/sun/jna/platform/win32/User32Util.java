/*
 * Copyright (c) 2013 Ralf Hamberger, Markus Karg, All Rights Reserved
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 */

package com.sun.jna.platform.win32;
import java.util.Arrays;
import java.util.List;

import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPVOID;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.RAWINPUTDEVICELIST;
import com.sun.jna.ptr.IntByReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Provides convenient usage of functions defined by {@code User32.dll}.
 *
 * @author Ralf HAMBERGER
 * @author Markus KARG (markus[at]headcrashing[dot]eu)
 */
public final class User32Util {
    public static final int registerWindowMessage(final String lpString) {
        final int messageId = User32.INSTANCE.RegisterWindowMessage(lpString);
        if (messageId == 0)
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
        return messageId;
    }

    public static final HWND createWindow(final String className, final String windowName, final int style, final int x, final int y, final int width,
            final int height, final HWND parent, final HMENU menu, final HINSTANCE instance, final LPVOID param) {
        return User32Util.createWindowEx(0, className, windowName, style, x, y, width, height, parent, menu, instance, param);
    }

    public static final HWND createWindowEx(final int exStyle, final String className, final String windowName, final int style, final int x, final int y,
            final int width, final int height, final HWND parent, final HMENU menu, final HINSTANCE instance, final LPVOID param) {
        final HWND hWnd = User32.INSTANCE
                .CreateWindowEx(exStyle, className, windowName, style, x, y, width, height, parent, menu, instance, param);
        if (hWnd == null)
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
        return hWnd;
    }

    public static final void destroyWindow(final HWND hWnd) {
        if (!User32.INSTANCE.DestroyWindow(hWnd))
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
    }

    public static final List<RAWINPUTDEVICELIST> GetRawInputDeviceList() {
        IntByReference puiNumDevices = new IntByReference(0);
        RAWINPUTDEVICELIST placeholder = new RAWINPUTDEVICELIST();
        int cbSize = placeholder.sizeof();
        // first call is with NULL so we query the expected number of devices
        int returnValue = User32.INSTANCE.GetRawInputDeviceList(null, puiNumDevices, cbSize);
        if (returnValue != 0) {
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
        }

        int deviceCount = puiNumDevices.getValue();
        RAWINPUTDEVICELIST[] records = (RAWINPUTDEVICELIST[]) placeholder.toArray(deviceCount);
        returnValue = User32.INSTANCE.GetRawInputDeviceList(records, puiNumDevices, cbSize);
        if (returnValue == (-1)) {
            throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
        }

        if (returnValue != records.length) {
            throw new IllegalStateException("Mismatched allocated (" + records.length + ") vs. received devices count (" + returnValue + ")");
        }

        return Arrays.asList(records);
    }
    
    /**
     * Helper class, that runs a windows message loop as a seperate thread.
     * 
     * This is intended to be used in conjunction with APIs, that need a
     * spinning message loop. One example for this are the DDE functions, that
     * can only be used if a message loop is present.
     * 
     * To enable interaction with the mainloop the MessageLoopThread allows to
     * dispatch callables into the mainloop and let these Callables be invoked
     * on the message thread.
     * 
     * This implies, that the Callables should block the loop as short as possible.
     */
    public static class MessageLoopThread extends Thread {

        public class Handler implements InvocationHandler {

            private final Object delegate;

            public Handler(Object delegate) {
                this.delegate = delegate;
            }

            public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
                try {
                    return MessageLoopThread.this.runOnThread(new Callable<Object>() {
                        public Object call() throws Exception {
                            return method.invoke(delegate, args);
                        }
                    });
                } catch (InvocationTargetException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception) {
                        StackTraceElement[] hiddenStack = cause.getStackTrace();
                        cause.fillInStackTrace();
                        StackTraceElement[] currentStack = cause.getStackTrace();
                        StackTraceElement[] fullStack = new StackTraceElement[currentStack.length + hiddenStack.length];
                        System.arraycopy(hiddenStack, 0, fullStack, 0, hiddenStack.length);
                        System.arraycopy(currentStack, 0, fullStack, hiddenStack.length, currentStack.length);
                        cause.setStackTrace(fullStack);
                        throw (Exception) cause;
                    } else {
                        throw ex;
                    }
                }
            }
        }
        
        private volatile int nativeThreadId = 0;
        private volatile long javaThreadId = 0;
        private final List<FutureTask> workQueue = Collections.synchronizedList(new ArrayList<FutureTask>());
        
        @Override
        public void run() {
            MSG msg = new WinUser.MSG();
            
            // Make sure message loop is prepared
            User32.INSTANCE.PeekMessage(msg, null, 0, 0, 0);
            javaThreadId = Thread.currentThread().getId();
            nativeThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
            
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) != 0) {
                while(! workQueue.isEmpty()) {
                    try {
                        FutureTask ft = workQueue.remove(0);
                        ft.run();
                    } catch (IndexOutOfBoundsException ex) {
                        break;
                    }
                }
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
            
            while (!workQueue.isEmpty()) {
                workQueue.remove(0).cancel(false);
            }
        }
        
        public <V> Future<V> runAsync(Callable<V> command) {
            while(nativeThreadId == 0) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Logger.getLogger(MessageLoopThread.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            FutureTask<V> futureTask = new FutureTask<V>(command);
            workQueue.add(futureTask);
            User32.INSTANCE.PostThreadMessage(nativeThreadId, WinUser.WM_USER, null, null);
            return futureTask;
        }
        
        public <V> V runOnThread(Callable<V> callable) throws Exception {
            while (javaThreadId == 0) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Logger.getLogger(MessageLoopThread.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            if(javaThreadId == Thread.currentThread().getId()) {
                return callable.call();
            } else {

                Future<V> ft = runAsync(callable);
                try {
                    return ft.get();
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    } else {
                        throw ex;
                    }
                }
            }
        }
        
        public void exit() {
            User32.INSTANCE.PostThreadMessage(nativeThreadId, WinUser.WM_QUIT, null, null);
        }
    }
}
