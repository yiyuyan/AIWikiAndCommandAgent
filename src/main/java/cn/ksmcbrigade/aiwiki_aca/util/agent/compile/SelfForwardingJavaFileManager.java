/*
 * Copyright (c) 2005, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package cn.ksmcbrigade.aiwiki_aca.util.agent.compile;

import cn.ksmcbrigade.aiwiki_aca.McChatbot;
import cn.ksmcbrigade.aiwiki_aca.util.agent.UnsafeUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;

/**
 * Forwards calls to a given file manager.  Subclasses of this class
 * might override some of these methods and might also provide
 * additional fields and methods.
 *
 * <p>Unless stated otherwise, references in this class to "<em>this file manager</em>"
 * should be interpreted as referring indirectly to the {@linkplain #fileManager delegate file manager}.
 *
 * @since 1.6
 */
public class SelfForwardingJavaFileManager implements JavaFileManager {

    /**
     * The file manager to which all methods are delegated.
     */
    protected final JavaFileManager fileManager;

    private Map<String, ByteArrayOutputStream> outputStreamMap;

    /**
     * Creates a new instance of {@code ForwardingJavaFileManager}.
     * @param fileManager delegate to this file manager
     */
    public SelfForwardingJavaFileManager(Map<String, ByteArrayOutputStream> outputStreamMap, JavaFileManager fileManager) {
        this.fileManager = Objects.requireNonNull(fileManager);
        this.outputStreamMap = outputStreamMap;
    }

    /**
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public ClassLoader getClassLoader(Location location) {
        return fileManager.getClassLoader(location);
    }

    /**
     * @throws IOException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<Kind> kinds,
                                         boolean recurse)
            throws IOException
    {
        return fileManager.list(location, packageName, kinds, recurse);
    }

    /**
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return fileManager.inferBinaryName(location, file);
    }

    /**
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return fileManager.isSameFile(a, b);
    }

    /**
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return fileManager.handleOption(current, remaining);
    }

    @Override
    public boolean hasLocation(Location location) {
        return fileManager.hasLocation(location);
    }

    @Override
    public int isSupportedOption(String option) {
        return fileManager.isSupportedOption(option);
    }

    /**
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public JavaFileObject getJavaFileForInput(Location location,
                                              String className,
                                              Kind kind)
            throws IOException
    {
        return fileManager.getJavaFileForInput(location, className, kind);
    }

    /**
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public JavaFileObject getJavaFileForOutput(Location location,
                                               String className,
                                               Kind kind,
                                               FileObject sibling)
            throws IOException
    {
        if(kind==Kind.CLASS){
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outputStreamMap.put(className,baos);
            try {
                SimpleJavaFileObject obj = simpleJavaFileObj(URI.create("bytes:///"+className.replace(".","/")+".class"),kind);
                return (JavaFileObject) Proxy.newProxyInstance(
                        JavaFileObject.class.getClassLoader(),
                        new Class<?>[]{JavaFileObject.class},
                        (proxy, method, args) -> {
                            if ("openOutputStream".equals(method.getName())) {
                                return baos;
                            }
                            return method.invoke(obj, args);
                        });
            } catch (Throwable e) {
                McChatbot.LOGGER.error("Failed to create simple java file obj.",e);
            }
        }
        return fileManager.getJavaFileForOutput(location, className, kind, sibling);
    }

    private SimpleJavaFileObject simpleJavaFileObj(URI uri, Kind kind) throws Throwable {
        MethodHandle handle = UnsafeUtils.lookup.findConstructor(SimpleJavaFileObject.class, MethodType.methodType(void.class, URI.class, Kind.class));
        return (SimpleJavaFileObject) handle.invoke(uri,kind);
    }

    /**{@inheritDoc}
     *
     * @implSpec If the subclass of the {@code ForwardingJavaFileManager} overrides the
     * {@link #getJavaFileForOutput} method, this method will delegate to it as per the
     * general contract of {@link JavaFileManager#getJavaFileForOutputForOriginatingFiles}.
     * If the subclass does not override the method, the call will be delegated to the
     * {@code fileManager}.
     *
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     *
     * @since 18
     */
    @Override
    public JavaFileObject getJavaFileForOutputForOriginatingFiles(Location location,
                                                                  String className,
                                                                  Kind kind,
                                                                  FileObject... originatingFiles) throws IOException {
        try {
            Method delegate = getClass().getMethod("getJavaFileForOutput",
                    Location.class, String.class,
                    Kind.class, FileObject.class);
            if (delegate.getDeclaringClass() == SelfForwardingJavaFileManager.class) {
                return fileManager.getJavaFileForOutputForOriginatingFiles(location, className,
                        kind, originatingFiles);
            } else {
                return JavaFileManager.super
                        .getJavaFileForOutputForOriginatingFiles(location, className,
                                kind, originatingFiles);
            }
        } catch (NoSuchMethodException ex) {
            throw new InternalError("This should never happen.", ex);
        }
    }

    /**
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public FileObject getFileForInput(Location location,
                                      String packageName,
                                      String relativeName)
            throws IOException
    {
        return fileManager.getFileForInput(location, packageName, relativeName);
    }

    /**
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public FileObject getFileForOutput(Location location,
                                       String packageName,
                                       String relativeName,
                                       FileObject sibling)
            throws IOException
    {
        return fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }

    /**{@inheritDoc}
     *
     * @implSpec If the subclass of the {@code ForwardingJavaFileManager} overrides the
     * {@link #getFileForOutput} method, this method will delegate to it as per the
     * general contract of {@link JavaFileManager#getFileForOutputForOriginatingFiles}.
     * If the subclass does not override the method, the call will be delegated to the
     * {@code fileManager}.
     *
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException {@inheritDoc}
     *
     * @since 18
     */
    @Override
    public FileObject getFileForOutputForOriginatingFiles(Location location,
                                                          String packageName,
                                                          String relativeName,
                                                          FileObject... originatingFiles) throws IOException {
        try {
            Method delegate = getClass().getMethod("getFileForOutput",
                    Location.class, String.class,
                    String.class, FileObject.class);
            if (delegate.getDeclaringClass() == SelfForwardingJavaFileManager.class) {
                return fileManager.getFileForOutputForOriginatingFiles(location, packageName,
                        relativeName, originatingFiles);
            } else {
                return JavaFileManager.super
                        .getFileForOutputForOriginatingFiles(location, packageName,
                                relativeName, originatingFiles);
            }
        } catch (NoSuchMethodException ex) {
            throw new InternalError("This should never happen.", ex);
        }
    }

    @Override
    public void flush() throws IOException {
        fileManager.flush();
    }

    @Override
    public void close() throws IOException {
        fileManager.close();
    }

    /**
     * @since 9
     */
    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        return fileManager.getLocationForModule(location, moduleName);
    }

    /**
     * @since 9
     */
    @Override
    public Location getLocationForModule(Location location, JavaFileObject fo) throws IOException {
        return fileManager.getLocationForModule(location, fo);
    }

    /**
     * @since 9
     */
    @Override
    public <S> ServiceLoader<S> getServiceLoader(Location location, Class<S> service) throws  IOException {
        return fileManager.getServiceLoader(location, service);
    }

    /**
     * @since 9
     */
    @Override
    public String inferModuleName(Location location) throws IOException {
        return fileManager.inferModuleName(location);
    }

    /**
     * @since 9
     */
    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        return fileManager.listLocationsForModules(location);
    }

    /**
     * @since 9
     */
    @Override
    public boolean contains(Location location, FileObject fo) throws IOException {
        return fileManager.contains(location, fo);
    }
}
