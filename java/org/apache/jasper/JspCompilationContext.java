/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jasper;

import org.apache.jasper.compiler.Compiler;
import org.apache.jasper.compiler.*;
import org.apache.jasper.servlet.JasperLoader;
import org.apache.jasper.servlet.JspServletWrapper;

import javax.servlet.ServletContext;
import javax.servlet.jsp.tagext.TagInfo;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A place holder for various things that are used through out the JSP
 * engine. This is a per-request/per-context data structure. Some of
 * the instance variables are set at different points.
 * <p/>
 * Most of the path-related stuff is here - mangling names, versions, dirs,
 * loading resources and dealing with uris.
 *
 * @author Anil K. Vijendran
 * @author Harish Prabandham
 * @author Pierre Delisle
 * @author Costin Manolache
 * @author Kin-man Chung
 */
public class JspCompilationContext
{

    static Object outputDirLock = new Object();
    protected org.apache.juli.logging.Log log =
            org.apache.juli.logging.LogFactory.getLog(JspCompilationContext.class);
    protected Map<String, URL> tagFileJarUrls;
    protected boolean isPackagedTagFile;
    protected String className;
    protected String jspUri;
    protected boolean isErrPage;
    protected String basePackageName;
    protected String derivedPackageName;
    protected String servletJavaFileName;
    protected String javaPath;
    protected String classFileName;
    protected String contentType;
    protected ServletWriter writer;
    protected Options options;
    protected JspServletWrapper jsw;
    protected Compiler jspCompiler;
    protected String classPath;
    protected String baseURI;
    protected String outputDir;
    protected ServletContext context;
    protected URLClassLoader loader;
    protected JspRuntimeContext rctxt;
    protected volatile int removed = 0;
    protected URLClassLoader jspLoader;
    protected URL baseUrl;
    protected Class servletClass;
    protected boolean isTagFile;
    protected boolean protoTypeMode;
    protected TagInfo tagInfo;
    protected URL tagFileJarUrl;

    // jspURI _must_ be relative to the context
    public JspCompilationContext(String jspUri,
                                 boolean isErrPage,
                                 Options options,
                                 ServletContext context,
                                 JspServletWrapper jsw,
                                 JspRuntimeContext rctxt)
    {

        this.jspUri = canonicalURI(jspUri);
        this.isErrPage = isErrPage;
        this.options = options;
        this.jsw = jsw;
        this.context = context;

        this.baseURI = jspUri.substring(0, jspUri.lastIndexOf('/') + 1);
        // hack fix for resolveRelativeURI
        if (baseURI == null)
        {
            baseURI = "/";
        } else if (baseURI.charAt(0) != '/')
        {
            // strip the basde slash since it will be combined with the
            // uriBase to generate a file
            baseURI = "/" + baseURI;
        }
        if (baseURI.charAt(baseURI.length() - 1) != '/')
        {
            baseURI += '/';
        }

        this.rctxt = rctxt;
        this.tagFileJarUrls = new HashMap<String, URL>();
        this.basePackageName = Constants.JSP_PACKAGE_NAME;
    }

    /* ==================== Methods to override ==================== */

    /** ---------- Class path and loader ---------- */

    public JspCompilationContext(String tagfile,
                                 TagInfo tagInfo,
                                 Options options,
                                 ServletContext context,
                                 JspServletWrapper jsw,
                                 JspRuntimeContext rctxt,
                                 URL tagFileJarUrl)
    {
        this(tagfile, false, options, context, jsw, rctxt);
        this.isTagFile = true;
        this.tagInfo = tagInfo;
        this.tagFileJarUrl = tagFileJarUrl;
        if (tagFileJarUrl != null)
        {
            isPackagedTagFile = true;
        }
    }

    protected static final boolean isPathSeparator(char c)
    {
        return (c == '/' || c == '\\');
    }

    protected static final String canonicalURI(String s)
    {
        if (s == null) return null;
        StringBuffer result = new StringBuffer();
        final int len = s.length();
        int pos = 0;
        while (pos < len)
        {
            char c = s.charAt(pos);
            if (isPathSeparator(c))
            {
               /*
                * multiple path separators.
                * 'foo///bar' -> 'foo/bar'
                */
                while (pos + 1 < len && isPathSeparator(s.charAt(pos + 1)))
                {
                    ++pos;
                }

                if (pos + 1 < len && s.charAt(pos + 1) == '.')
                {
                   /*
                    * a single dot at the end of the path - we are done.
                    */
                    if (pos + 2 >= len) break;

                    switch (s.charAt(pos + 2))
                    {
                       /*
                        * self directory in path
                        * foo/./bar -> foo/bar
                        */
                        case '/':
                        case '\\':
                            pos += 2;
                            continue;

                       /*
                        * two dots in a path: go back one hierarchy.
                        * foo/bar/../baz -> foo/baz
                        */
                        case '.':
                            // only if we have exactly _two_ dots.
                            if (pos + 3 < len && isPathSeparator(s.charAt(pos + 3)))
                            {
                                pos += 3;
                                int separatorPos = result.length() - 1;
                                while (separatorPos >= 0 &&
                                        !isPathSeparator(result
                                                .charAt(separatorPos)))
                                {
                                    --separatorPos;
                                }
                                if (separatorPos >= 0)
                                    result.setLength(separatorPos);
                                continue;
                            }
                    }
                }
            }
            result.append(c);
            ++pos;
        }
        return result.toString();
    }

    /**
     * The classpath that is passed off to the Java compiler.
     */
    public String getClassPath()
    {
        if (classPath != null)
            return classPath;
        return rctxt.getClassPath();
    }

    /**
     * The classpath that is passed off to the Java compiler.
     */
    public void setClassPath(String classPath)
    {
        this.classPath = classPath;
    }

    /** ---------- Input/Output  ---------- */

    /**
     * What class loader to use for loading classes while compiling
     * this JSP?
     */
    public ClassLoader getClassLoader()
    {
        if (loader != null)
            return loader;
        return rctxt.getParentClassLoader();
    }

    public void setClassLoader(URLClassLoader loader)
    {
        this.loader = loader;
    }

    public ClassLoader getJspLoader()
    {
        if (jspLoader == null)
        {
            jspLoader = new JasperLoader
                    (new URL[]{baseUrl},
                            getClassLoader(),
                            rctxt.getPermissionCollection(),
                            rctxt.getCodeSource());
        }
        return jspLoader;
    }

    /**
     * The output directory to generate code into.  The output directory
     * is make up of the scratch directory, which is provide in Options,
     * plus the directory derived from the package name.
     */
    public String getOutputDir()
    {
        if (outputDir == null)
        {
            createOutputDir();
        }

        return outputDir;
    }

    /** ---------- Access resources in the webapp ---------- */

    /**
     * Create a "Compiler" object based on some init param data. This
     * is not done yet. Right now we're just hardcoding the actual
     * compilers that are created.
     */
    public Compiler createCompiler() throws JasperException
    {
        if (jspCompiler != null)
        {
            return jspCompiler;
        }
        jspCompiler = null;
        if (options.getCompilerClassName() != null)
        {
            jspCompiler = createCompiler(options.getCompilerClassName());
        } else
        {
            if (options.getCompiler() == null)
            {
                jspCompiler = createCompiler("org.apache.jasper.compiler.JDTCompiler");
                if (jspCompiler == null)
                {
                    jspCompiler = createCompiler("org.apache.jasper.compiler.AntCompiler");
                }
            } else
            {
                jspCompiler = createCompiler("org.apache.jasper.compiler.AntCompiler");
                if (jspCompiler == null)
                {
                    jspCompiler = createCompiler("org.apache.jasper.compiler.JDTCompiler");
                }
            }
        }
        if (jspCompiler == null)
        {
            throw new IllegalStateException(Localizer.getMessage("jsp.error.compiler"));
        }
        jspCompiler.init(this, jsw);
        return jspCompiler;
    }

    protected Compiler createCompiler(String className)
    {
        Compiler compiler = null;
        try
        {
            compiler = (Compiler) Class.forName(className).newInstance();
        }
        catch (InstantiationException e)
        {
            log.warn(Localizer.getMessage("jsp.error.compiler"), e);
        }
        catch (IllegalAccessException e)
        {
            log.warn(Localizer.getMessage("jsp.error.compiler"), e);
        }
        catch (NoClassDefFoundError e)
        {
            if (log.isDebugEnabled())
            {
                log.debug(Localizer.getMessage("jsp.error.compiler"), e);
            }
        }
        catch (ClassNotFoundException e)
        {
            if (log.isDebugEnabled())
            {
                log.debug(Localizer.getMessage("jsp.error.compiler"), e);
            }
        }
        return compiler;
    }

    public Compiler getCompiler()
    {
        return jspCompiler;
    }

    /**
     * Get the full value of a URI relative to this compilations context
     * uses current file as the base.
     */
    public String resolveRelativeUri(String uri)
    {
        // sometimes we get uri's massaged from File(String), so check for
        // a root directory deperator char
        if (uri.startsWith("/") || uri.startsWith(File.separator))
        {
            return uri;
        } else
        {
            return baseURI + uri;
        }
    }

    /**
     * Gets a resource as a stream, relative to the meanings of this
     * context's implementation.
     *
     * @return a null if the resource cannot be found or represented
     * as an InputStream.
     */
    public java.io.InputStream getResourceAsStream(String res)
    {
        return context.getResourceAsStream(canonicalURI(res));
    }

    public URL getResource(String res) throws MalformedURLException
    {
        URL result = null;

        if (res.startsWith("/META-INF/"))
        {
            // This is a tag file packaged in a jar that is being compiled
            URL jarUrl = tagFileJarUrls.get(res);
            if (jarUrl == null)
            {
                jarUrl = tagFileJarUrl;
            }
            if (jarUrl != null)
            {
                result = new URL(jarUrl.toExternalForm() + res.substring(1));
            } else
            {
                // May not be in a JAR in some IDE environments
                result = context.getResource(canonicalURI(res));
            }
        } else if (res.startsWith("jar:file:"))
        {
            // This is a tag file packaged in a jar that is being checked
            // for a dependency
            result = new URL(res);

        } else
        {
            result = context.getResource(canonicalURI(res));
        }
        return result;
    }

    public Set getResourcePaths(String path)
    {
        return context.getResourcePaths(canonicalURI(path));
    }

    /**
     * Gets the actual path of a URI relative to the context of
     * the compilation.
     */
    public String getRealPath(String path)
    {
        if (context != null)
        {
            return context.getRealPath(path);
        }
        return path;
    }

    /* ==================== Common implementation ==================== */

    /**
     * Returns the tag-file-name-to-JAR-file map of this compilation unit,
     * which maps tag file names to the JAR files in which the tag files are
     * packaged.
     * <p/>
     * The map is populated when parsing the tag-file elements of the TLDs
     * of any imported taglibs.
     */
    public URL getTagFileJarUrl(String tagFile)
    {
        return this.tagFileJarUrls.get(tagFile);
    }

    public void setTagFileJarUrl(String tagFile, URL tagFileURL)
    {
        this.tagFileJarUrls.put(tagFile, tagFileURL);
    }

    /**
     * Returns the JAR file in which the tag file for which this
     * JspCompilationContext was created is packaged, or null if this
     * JspCompilationContext does not correspond to a tag file, or if the
     * corresponding tag file is not packaged in a JAR.
     */
    public URL getTagFileJarUrl()
    {
        return this.tagFileJarUrl;
    }

    /**
     * Just the class name (does not include package name) of the
     * generated class.
     */
    public String getServletClassName()
    {

        if (className != null)
        {
            return className;
        }

        if (isTagFile)
        {
            className = tagInfo.getTagClassName();
            int lastIndex = className.lastIndexOf('.');
            if (lastIndex != -1)
            {
                className = className.substring(lastIndex + 1);
            }
        } else
        {
            int iSep = jspUri.lastIndexOf('/') + 1;
            className = JspUtil.makeJavaIdentifier(jspUri.substring(iSep));
        }
        return className;
    }

    public void setServletClassName(String className)
    {
        this.className = className;
    }

    /**
     * Path of the JSP URI. Note that this is not a file name. This is
     * the context rooted URI of the JSP file.
     */
    public String getJspFile()
    {
        return jspUri;
    }

    /**
     * Are we processing something that has been declared as an
     * errorpage?
     */
    public boolean isErrorPage()
    {
        return isErrPage;
    }

    public void setErrorPage(boolean isErrPage)
    {
        this.isErrPage = isErrPage;
    }

    public boolean isTagFile()
    {
        return isTagFile;
    }

    public TagInfo getTagInfo()
    {
        return tagInfo;
    }

    public void setTagInfo(TagInfo tagi)
    {
        tagInfo = tagi;
    }

    /**
     * True if we are compiling a tag file in prototype mode.
     * ie we only generate codes with class for the tag handler with empty
     * method bodies.
     */
    public boolean isPrototypeMode()
    {
        return protoTypeMode;
    }

    public void setPrototypeMode(boolean pm)
    {
        protoTypeMode = pm;
    }

    /**
     * Package name for the generated class is make up of the base package
     * name, which is user settable, and the derived package name.  The
     * derived package name directly mirrors the file heirachy of the JSP page.
     */
    public String getServletPackageName()
    {
        if (isTagFile())
        {
            String className = tagInfo.getTagClassName();
            int lastIndex = className.lastIndexOf('.');
            String pkgName = "";
            if (lastIndex != -1)
            {
                pkgName = className.substring(0, lastIndex);
            }
            return pkgName;
        } else
        {
            String dPackageName = getDerivedPackageName();
            if (dPackageName.length() == 0)
            {
                return basePackageName;
            }
            return basePackageName + '.' + getDerivedPackageName();
        }
    }

    /**
     * The package name into which the servlet class is generated.
     */
    public void setServletPackageName(String servletPackageName)
    {
        this.basePackageName = servletPackageName;
    }

    protected String getDerivedPackageName()
    {
        if (derivedPackageName == null)
        {
            int iSep = jspUri.lastIndexOf('/');
            derivedPackageName = (iSep > 0) ?
                    JspUtil.makeJavaPackage(jspUri.substring(1, iSep)) : "";
        }
        return derivedPackageName;
    }

    /**
     * Full path name of the Java file into which the servlet is being
     * generated.
     */
    public String getServletJavaFileName()
    {
        if (servletJavaFileName == null)
        {
            servletJavaFileName = getOutputDir() + getServletClassName() + ".java";
        }
        return servletJavaFileName;
    }

    /**
     * Get hold of the Options object for this context.
     */
    public Options getOptions()
    {
        return options;
    }

    public ServletContext getServletContext()
    {
        return context;
    }

    public JspRuntimeContext getRuntimeContext()
    {
        return rctxt;
    }

    /**
     * Path of the Java file relative to the work directory.
     */
    public String getJavaPath()
    {

        if (javaPath != null)
        {
            return javaPath;
        }

        if (isTagFile())
        {
            String tagName = tagInfo.getTagClassName();
            javaPath = tagName.replace('.', '/') + ".java";
        } else
        {
            javaPath = getServletPackageName().replace('.', '/') + '/' +
                    getServletClassName() + ".java";
        }
        return javaPath;
    }

    public String getClassFileName()
    {
        if (classFileName == null)
        {
            classFileName = getOutputDir() + getServletClassName() + ".class";
        }
        return classFileName;
    }

    /**
     * Get the content type of this JSP.
     * <p/>
     * Content type includes content type and encoding.
     */
    public String getContentType()
    {
        return contentType;
    }

    public void setContentType(String contentType)
    {
        this.contentType = contentType;
    }

    /**
     * Where is the servlet being generated?
     */
    public ServletWriter getWriter()
    {
        return writer;
    }

    // ==================== Removal ==================== 

    public void setWriter(ServletWriter writer)
    {
        this.writer = writer;
    }

    /**
     * Gets the 'location' of the TLD associated with the given taglib 'uri'.
     *
     * @return An array of two Strings: The first element denotes the real
     * path to the TLD. If the path to the TLD points to a jar file, then the
     * second element denotes the name of the TLD entry in the jar file.
     * Returns null if the given uri is not associated with any tag library
     * 'exposed' in the web application.
     */
    public String[] getTldLocation(String uri) throws JasperException
    {
        String[] location =
                getOptions().getTldLocationsCache().getLocation(uri);
        return location;
    }

    // ==================== Compile and reload ====================

    /**
     * Are we keeping generated code around?
     */
    public boolean keepGenerated()
    {
        return getOptions().getKeepGenerated();
    }

    // ==================== Manipulating the class ====================

    public void incrementRemoved()
    {
        if (removed == 0 && rctxt != null)
        {
            rctxt.removeWrapper(jspUri);
        }
        removed++;
    }

    // ==================== protected methods ==================== 

    public boolean isRemoved()
    {
        if (removed > 0)
        {
            return true;
        }
        return false;
    }

    public void compile() throws JasperException, FileNotFoundException
    {
        createCompiler();
        if (jspCompiler.isOutDated())
        {
            if (isRemoved())
            {
                throw new FileNotFoundException(jspUri);
            }
            try
            {
                jspCompiler.removeGeneratedFiles();
                jspLoader = null;
                jspCompiler.compile();
                jsw.setReload(true);
                jsw.setCompilationException(null);
            }
            catch (JasperException ex)
            {
                // Cache compilation exception
                jsw.setCompilationException(ex);
                if (options.getDevelopment() && options.getRecompileOnFail())
                {
                    // Force a recompilation attempt on next access
                    jsw.setLastModificationTest(-1);
                }
                throw ex;
            }
            catch (Exception ex)
            {
                JasperException je = new JasperException(
                        Localizer.getMessage("jsp.error.unable.compile"),
                        ex);
                // Cache compilation exception
                jsw.setCompilationException(je);
                throw je;
            }
        }
    }

    public Class load()
            throws JasperException, FileNotFoundException
    {
        try
        {
            getJspLoader();

            String name;
            if (isTagFile())
            {
                name = tagInfo.getTagClassName();
            } else
            {
                name = getServletPackageName() + "." + getServletClassName();
            }
            servletClass = jspLoader.loadClass(name);
        }
        catch (ClassNotFoundException cex)
        {
            throw new JasperException(Localizer.getMessage("jsp.error.unable.load"),
                    cex);
        }
        catch (Exception ex)
        {
            throw new JasperException(Localizer.getMessage("jsp.error.unable.compile"),
                    ex);
        }
        removed = 0;
        return servletClass;
    }

    public void checkOutputDir()
    {
        if (outputDir != null)
        {
            if (!(new File(outputDir)).exists())
            {
                makeOutputDir();
            }
        } else
        {
            createOutputDir();
        }
    }

    protected boolean makeOutputDir()
    {
        synchronized (outputDirLock)
        {
            File outDirFile = new File(outputDir);
            return (outDirFile.exists() || outDirFile.mkdirs());
        }
    }

    protected void createOutputDir()
    {
        String path = null;
        if (isTagFile())
        {
            String tagName = tagInfo.getTagClassName();
            path = tagName.replace('.', File.separatorChar);
            path = path.substring(0, path.lastIndexOf(File.separatorChar));
        } else
        {
            path = getServletPackageName().replace('.', File.separatorChar);
        }

        // Append servlet or tag handler path to scratch dir
        try
        {
            File base = options.getScratchDir();
            baseUrl = base.toURI().toURL();
            outputDir = base.getAbsolutePath() + File.separator + path +
                    File.separator;
            if (!makeOutputDir())
            {
                throw new IllegalStateException(Localizer.getMessage("jsp.error.outputfolder"));
            }
        }
        catch (MalformedURLException e)
        {
            throw new IllegalStateException(Localizer.getMessage("jsp.error.outputfolder"), e);
        }
    }
}

