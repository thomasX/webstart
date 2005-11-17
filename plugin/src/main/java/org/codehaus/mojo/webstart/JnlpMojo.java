package org.codehaus.mojo.webstart;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License" );
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.InvalidPluginException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.jar.JarSignMojo;
import org.apache.maven.plugin.version.PluginVersionNotFoundException;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.project.DefaultMavenProjectHelper;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.settings.Settings;

import org.codehaus.mojo.keytool.GenkeyMojo;
import org.codehaus.mojo.webstart.generator.Generator;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.FileUtils;

import org.apache.commons.lang.SystemUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Packages a jnlp application.
 *
 * The plugin tries to not re-sign/re-pack if the dependent jar hasn't changed.
 * As a consequence, if one modifies the pom jnlp config or a keystore, one should clean before rebuilding.
 *
 * @author <a href="jerome@coffeebreaks.org">Jerome Lacoste</a>
 * @version $Id: $
 * @goal jnlp
 * @phase package
 * @requiresDependencyResolution runtime
 * @requiresProject
 * @inheritedByDefault true
 * @todo refactor the common code with javadoc plugin
 * @todo how to propagate the -X argument to enable verbose?
 * @todo initialize the jnlp alias and dname.o from pom.artifactId and pom.organization.name
 */
public class JnlpMojo
    extends AbstractMojo
{
    /**
     * Directory to create the resulting artifacts
     *
     * @parameter expression="${project.build.directory}/jnlp"
     * @required
     */
    protected File workDirectory;

    /**
     * The Zip archiver.
     *
     * @parameter expression="${component.org.codehaus.plexus.archiver.Archiver#zip}"
     * @required
     */
    private ZipArchiver zipArchiver;

    /**
     * Project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * JNLP dependencies.
     *
     * @parameter
     */
    private List dependencies;

    /**
     * Xxx
     *
     * @parameter
     */
    private JnlpConfig jnlp;

    /**
     * Xxx
     *
     * @parameter
     */
    private SignConfig sign;

    public static class KeystoreConfig
    {
        private boolean delete;

        private boolean gen;

        public boolean isDelete()
        {
            return delete;
        }

        public void setDelete( boolean delete )
        {
            this.delete = delete;
        }

        public boolean isGen()
        {
            return gen;
        }

        public void setGen( boolean gen )
        {
            this.gen = gen;
        }
    }

    /**
     * Xxx
     *
     * @parameter
     */
    private KeystoreConfig keystore;

    /**
     * Xxx
     *
     * @parameter default-value="false"
     */
    private boolean usejnlpservlet;

    /**
     * Xxx
     *
     * @parameter default-value="true"
     */
    private boolean verifyjar;

    /**
     * Enables pack200. Requires SDK 5.0.
     *
     * @parameter default-value="false"
     */
    private boolean pack200;

    /**
     * Xxx
     *
     * @parameter default-value="false"
     */
    private boolean gzip;

    /**
     * Enable verbose.
     *
     * @parameter expression="${verbose}" default-value="false"
     */
    private boolean verbose;

    /**
     * @parameter expression="${basedir}"
     * @required
     * @readonly
     */
    private File basedir;

    /**
     * Artifact resolver, needed to download source jars for inclusion in classpath.
     *
     * @parameter expression="${component.org.apache.maven.artifact.resolver.ArtifactResolver}"
     * @required
     * @readonly
     * @todo waiting for the component tag
     */
    private ArtifactResolver artifactResolver;

    /**
     * Artifact factory, needed to download source jars for inclusion in classpath.
     *
     * @parameter expression="${component.org.apache.maven.artifact.factory.ArtifactFactory}"
     * @required
     * @readonly
     * @todo waiting for the component tag
     */
    private ArtifactFactory artifactFactory;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * @parameter expression="${component.org.apache.maven.project.MavenProjectHelper}
     */
    private MavenProjectHelper projectHelper;

    /**
     * The current user system settings for use in Maven. This is used for
     * <br/>
     * plugin manager API calls.
     *
     * @parameter expression="${settings}"
     * @required
     * @readonly
     */
    private Settings settings;

    /**
     * The plugin manager instance used to resolve plugin descriptors.
     *
     * @component role="org.apache.maven.plugin.PluginManager"
     */
    private PluginManager pluginManager;

    private class CompositeFileFilter implements FileFilter {
        private List fileFilters = new ArrayList();

        CompositeFileFilter( FileFilter filter1, FileFilter filter2 ) {
            fileFilters.add( filter1 );
            fileFilters.add( filter2 );
        }
 
        public boolean accept( File pathname )
        {
            for ( int i = 0; i < fileFilters.size(); i++ ) {
                if ( ! ((FileFilter) fileFilters.get(i)).accept( pathname ) ) 
                    return false; 
            }
            return true;
        }

    }

    private FileFilter modifiedFileFilter = new FileFilter()
    {
        public boolean accept( File pathname )
        {
            boolean modified = pathname.lastModified() > getStartTime();
            getLog().debug( "File: " + pathname.getName() + " modified: " + modified );
            getLog().debug( "lastModified: " + pathname.lastModified() + " plugin start time " + getStartTime() );
            return modified;
        }
    };

    private FileFilter jarFileFilter = new FileFilter()
    {
        public boolean accept( File pathname )
        {
            return pathname.isFile() && pathname.getName().endsWith( ".jar" );
        }
    };

    private FileFilter pack200FileFilter = new FileFilter()
    {
        public boolean accept( File pathname )
        {
            return pathname.isFile() &&
                ( pathname.getName().endsWith( ".jar.pack.gz" ) || pathname.getName().endsWith( ".jar.pack" ) );
        }
    };

    // the jars to sign and pack are selected if they are newer than the plugin start.
    // as the plugin copies the new versions locally before signing/packing them
    // we just need to see if the plugin copied a new version
    // We achieve that by only filtering files modified after the plugin was started
    // FIXME we may want to also resign/repack the jars if other files (the pom, the keystore config) have changed
    // today one needs to clean...
    private FileFilter updatedJarFileFilter = new CompositeFileFilter( jarFileFilter, modifiedFileFilter );

    private FileFilter updatedPack200FileFilter = new CompositeFileFilter( pack200FileFilter, modifiedFileFilter );

    // FIXME ill-chosen name. Now that optimization takes place, some artifacts are in the list even though they aren't copied
    private List copiedArtifacts;

    private Artifact artifactWithMainClass;

    // initialized by execute
    private long startTime;

    private long getStartTime() {
        if ( startTime == 0 ) {
             throw new IllegalStateException( "startTime not initialized" );
        }
        return startTime;
    }

    public void execute()
        throws MojoExecutionException
    {

        checkInput();

        // interesting: copied files lastModified time stamp will be rounded.
        // We have to be sure that the startTime is before that time...
        // rounding to the second - 1 millis should be sufficient..
        startTime = System.currentTimeMillis() - 1000; 

        // We keep track of the list of copied artifacts for debug purposes (we can compare it to the list of signed/packed jars)
        List debugModifiedArtifacts = new ArrayList();

        File workDirectory = getWorkDirectory();
        getLog().debug( "using work directory " + workDirectory );
        //
        // prepare layout
        //
        if ( !workDirectory.exists() && !workDirectory.mkdirs() )
        {
            throw new MojoExecutionException( "Failed to create: " + workDirectory.getAbsolutePath() );
        }

        try
        {

            File applicationDirectory = workDirectory;
            File iconFolder = new File( applicationDirectory, "images" );
            iconFolder.mkdirs();

            //
            //copy icons, jars etc.. to the relevant folders
            //
            for ( int i = 0; i < jnlp.getInformations().length; i++ )
            {
                JnlpConfig.Information information = jnlp.getInformations()[i];
                if ( information.getIcons() != null )
                {
                    for ( int j = 0; j < information.getIcons().length; j++ )
                    {
                        //icons
                        JnlpConfig.Icon icon = information.getIcons()[j];
                        File iconFile = getIconFile( icon );
                        icon.setFileName( iconFile.getName() );
                        copyFileToDirectoryIfNecessary( iconFile, iconFolder );
                    }
                }
            }

            /*
            // jnlp servlet -> WEB-INF/lib folder
            if ( this.usejnlpservlet ) {
                // we need to retrieve the version of the jnlpServlet.
                String jnlpServletGroupId = "com.sun.java.jnlp";
                String jnlpServletArtifactId = "jnlp-servlet";
                String jnlpServletVersion = findThisPluginDependencyVersion( jnlpServletGroupId, jnlpServletArtifactId );

                // getLog().debug( "****************************************************************************" );
                Artifact jnlpServletArtifact = resolveJarArtifact( jnlpServletGroupId, jnlpServletArtifactId, jnlpServletVersion );
                getLog().debug( "jnlpServletArtifact : " + jnlpServletArtifact.getFile() );

                copyFileToDirectory( jnlpServletArtifact.getFile(), webinflibFolder );
            }
            */

            artifactWithMainClass = null;

            copiedArtifacts = new ArrayList();
            Collection artifacts = getProject().getArtifacts();
            for ( Iterator it = dependencies.iterator(); it.hasNext(); )
            {
                String dependency = (String) it.next();
                getLog().debug( "handling dependency " + dependency );

                boolean found = false;
                // identify artifact
                //jars -> application folder
                // similar to what war plugin does
                // FIXME we must make our list based on the specified dependencies
                for ( Iterator it2 = artifacts.iterator(); it2.hasNext() && !found; )
                {
                    Artifact artifact = (Artifact) it2.next();

                    // should we use depedencyset and filters like in assembly plugin?
                    if ( !matches( artifact, dependency ) )
                    {
                        continue;
                    }
                    found = true;

                    // copied from war plugin... then modified
                    // BEGIN COPY
                    // TODO: utilise appropriate methods from project builder
                    // TODO: scope handler
                    // Include runtime and compile time libraries
                    if ( !Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) &&
                        !Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
                    {
                        String type = artifact.getType();
                        if ( "jar".equals( type ) )
                        {
                            // FIXME when signed, we should update the manifest.
                            // see http://www.mail-archive.com/turbine-maven-dev@jakarta.apache.org/msg08081.html
                            // and maven1: maven-plugins/jnlp/src/main/org/apache/maven/jnlp/UpdateManifest.java
                            boolean copied = copyFileToDirectoryIfNecessary( artifact.getFile(), applicationDirectory );

                            if ( copied ) {

                                String name = artifact.getFile().getName();
                                debugModifiedArtifacts.add( name.substring( 0, name.lastIndexOf( '.' ) ) );

                            }

                            copiedArtifacts.add( artifact );

                            // JarArchiver.grabFilesAndDirs()
                            ClassLoader cl = new java.net.URLClassLoader( new URL[]{artifact.getFile().toURL()} );
                            try
                            {
                                Class.forName( jnlp.getMainClass(), false, cl );
                                if ( artifactWithMainClass == null )
                                {
                                    artifactWithMainClass = artifact;
                                    getLog().debug(
                                        "Found main jar. Artifact " + artifactWithMainClass +
                                        " contains the main class: " +
                                        jnlp.getMainClass() );
                                }
                                else
                                {
                                    getLog().warn(
                                        "artifact " + artifact + " also contains the main class: " +
                                        jnlp.getMainClass() +
                                        ". IGNORED." );
                                }
                            }
                            catch ( ClassNotFoundException e )
                            {
                                getLog().debug(
                                    "artifact " + artifact + " doesn't contain the main class: " + jnlp.getMainClass() );
                            }
                        }
                        else
                        // FIXME how do we deal with native libs?
                        // we should probably identify them and package inside jars that we timestamp like the native lib
                        // to avoid repackaging every time. What are the types of the native libs?
                        {
                            getLog().debug(
                                "Skipping artifact of type " + type + " for " + applicationDirectory.getName() );
                        }
                        // END COPY
                    }
                }
                if ( !found )
                {
                    throw new MojoExecutionException( "didn't find dependency " + dependency + " in dependency list." );
                }
            }
            if ( artifactWithMainClass == null )
            {
                throw new MojoExecutionException( "didn't find artifact with main class: " + jnlp.getMainClass() + ". Did you specify it? " );
            }

            // native libsi
            // FIXME

            /*
            for( Iterator it = getNativeLibs().iterator(); it.hasNext(); ) {
                Artifact artifact = ;
                Artifact copiedArtifact = 

                // similar to what we do for jars, except that we must pack them into jar instead of copying.
                // them
                    File nativeLib = artifact.getFile()
                    if(! nativeLib.endsWith( ".jar" ) ){
                        getLog().debug("Wrapping native library " + artifact + " into jar." );
                        File nativeLibJar = new File( applicationFolder, xxx + ".jar");
                        Jar jarTask = new Jar();
                        jarTask.setDestFile( nativeLib );
                        jarTask.setBasedir( basedir );
                        jarTask.setIncludes( nativeLib );
                        jarTask.execute();

                        nativeLibJar.setLastModified( nativeLib.lastModified() );
              
                        copiedArtifact = new ....
                    } else {
                        getLog().debug( "Copying native lib " + artifact );
                        copyFileToDirectory( artifact.getFile(), applicationFolder );
  
                        copiedArtifact = artifact;
                    }
                    copiedNativeArtifacts.add( copiedArtifact );
                }
            }
            */

            //
            // pack200 and jar signing
            //
            if ( ( pack200 || sign != null )
                 && getLog().isDebugEnabled() )
            {
                logCollection( "Some dependencies may be skipped. Here's the list of the artifacts that should be signed/packed: ",
                                debugModifiedArtifacts );
            }
 
            if ( sign != null )
            {

                if ( keystore != null && keystore.isGen() )
                {
                    if ( keystore.isDelete() )
                    {
                        deleteKeyStore();
                    }
                    genKeyStore();
                }

                if ( pack200 )
                {
                    // http://java.sun.com/j2se/1.5.0/docs/guide/deployment/deployment-guide/pack200.html
                    // we need to pack then unpack the files before signing them
                    Pack200.packJars( applicationDirectory, updatedJarFileFilter, this.gzip );
                    Pack200.unpackJars( applicationDirectory, updatedPack200FileFilter );
                    // specs says that one should do it twice when there are unsigned jars??
                    // Pack200.unpackJars( applicationDirectory, updatedPack200FileFilter );
                }

                int signedJars = signJars( applicationDirectory, updatedJarFileFilter );
                if ( signedJars != debugModifiedArtifacts.size() ) {
                    throw new IllegalStateException( 
                           "the number of signed artifacts differ from the number of modified artifacts. Implementation error" 
                       );
                }
            }
            if ( pack200 )
            {
                getLog().debug( "packing jars" );
                Pack200.packJars( applicationDirectory, updatedJarFileFilter, this.gzip );
            }

            //
            // template generation
            //
            // generate the JNLP deployment file
            File jnlpOutputFile = new File( applicationDirectory, "launch.jnlp" );
            Generator jnlpGenerator = new Generator( this, jnlpOutputFile,
                                                     "org/codehaus/mojo/webstart/template/jnlp.vm" );
            try
            {
                jnlpGenerator.generate();
            }
            catch ( Exception e )
            {
                getLog().debug( e.toString() );
                throw new MojoExecutionException( "Could not generate the JNLP deployment descriptor", e );
            }

            // package the zip. Note this is very simple. Look at the JarMojo which does more things.
            // we should perhaps package as a war when inside a project with war packaging ?
            File toFile = new File( project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".zip" );
            if ( toFile.exists() )
            {
                getLog().debug( "deleting file " + toFile );
                toFile.delete();
            }
            zipArchiver.addDirectory( workDirectory );
            zipArchiver.setDestFile( toFile );
            getLog().debug( "about to call createArchive" );
            zipArchiver.createArchive();

            // project.attachArtifact( "pom", null, toFile );

            getLog().debug( "**** attach new way **** " + projectHelper.getClass().getName() );
            // depends on MNG-1251
            projectHelper.attachArtifact( project, "zip", toFile );

        }
        catch ( MojoExecutionException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failure to run the plugin: ", e);
            /*
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter( sw, true );
            e.printStackTrace( pw );
            pw.flush();
            sw.flush();

            getLog().debug( "An error occurred during the task: " + sw.toString() );
            */
        }

    }

    private Artifact resolveJarArtifact( String groupId, String artifactId, String version )
        throws MojoExecutionException
    {
        return resolveArtifact( groupId, artifactId, version, "jar" );
    }

    private Artifact resolveArtifact( String groupId, String artifactId, String version, final String type )
        throws MojoExecutionException
    {
        Artifact artifact = artifactFactory.createArtifact( groupId, artifactId, version, null, type );

        try
        {
            artifactResolver.resolve( artifact, getProject().getRemoteArtifactRepositories(), localRepository );
        }
        catch ( ArtifactNotFoundException e )
        {
            // ignore, the jar has not been found
            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( "Cannot resolve source artifact", e );
            }
        }
        catch ( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "Error getting source artifact", e );
        }

        return artifact;
    }

    private void logCollection( final String prefix, final Collection collection )
    {
        getLog().debug( prefix + " " + collection );
        if ( collection == null )
        {
            return;
        }
        for ( Iterator it3 = collection.iterator(); it3.hasNext(); )
        {
            getLog().debug( prefix + it3.next() );
        }
    }

    private void deleteKeyStore()
    {
        File keyStore = null;
        if ( sign.getKeystore() != null )
        {
            keyStore = new File( sign.getKeystore() );
        }
        else
        {
            // FIXME decide if we really want this.
            // keyStore = new File( System.getProperty( "user.home") + File.separator + ".keystore" );
        }

        if ( keyStore == null )
        {
            return;
        }
        if ( keyStore.exists() )
        {
            if ( keyStore.delete() )
            {
                getLog().debug( "deleted keystore from: " + keyStore.getAbsolutePath() );
            }
            else
            {
                getLog().warn( "Couldn't delete keystore from: " + keyStore.getAbsolutePath() );
            }
        }
        else
        {
            getLog().debug( "Skipping deletion of non existing keystore: " + keyStore.getAbsolutePath() );
        }
    }

    private void genKeyStore()
        throws MojoExecutionException
    {
        GenkeyMojo genKeystore = new GenkeyMojo();
        genKeystore.setAlias( sign.getAlias() );
        genKeystore.setDname( sign.getDname() );
        genKeystore.setKeyalg( sign.getKeyalg() );
        genKeystore.setKeypass( sign.getKeypass() );
        genKeystore.setKeysize( sign.getKeysize() );
        genKeystore.setKeystore( sign.getKeystore() );
        genKeystore.setSigalg( sign.getSigalg() );
        genKeystore.setStorepass( sign.getStorepass() );
        genKeystore.setStoretype( sign.getStoretype() );
        genKeystore.setValidity( sign.getValidity() );
        genKeystore.setVerbose( this.verbose );
        genKeystore.setWorkingDir( getWorkDirectory() );

        genKeystore.execute();
    }

    private File getWorkDirectory()
    {
        return workDirectory;
    }

    private boolean matches( Artifact artifact, String dependency )
    {
        final String stringRepresentation = ArtifactUtils.versionlessKey( artifact );
        final boolean b = dependency.equals( stringRepresentation );
        getLog().debug( "checking match of <" + dependency + "> with <" + stringRepresentation + ">: " + b );
        return b;
    }

    /**
     * Copy only if the target doesn't exists or is outdated compared to the source.
     * @return whether or not the file was copied.
     */
    public boolean copyFileToDirectoryIfNecessary( File sourceFile, File targetDirectory ) throws IOException {

        File targetFile = new File( targetDirectory, sourceFile.getName() );

        boolean shouldCopy = ! targetFile.exists() 
             || targetFile.lastModified() < sourceFile.lastModified();

        if ( shouldCopy ) {

            FileUtils.copyFileToDirectory( sourceFile, targetDirectory );

        } else {

            getLog().debug( "Source file hasn't changed. Do not overwrite " + targetFile + " with " + sourceFile + ".");

        }
        return shouldCopy;
    }

    private File getIconFile( JnlpConfig.Icon icon )
        throws MojoExecutionException
    {
        // FIXME we could have a different priority search order. IN particular the src/jnlp/icons could be first.
        File iconFile = new File( icon.getHref() );
        if ( !iconFile.exists() )
        {
            getLog().debug( "icon " + icon.getHref() + " not found at " + iconFile.getAbsolutePath() );
            iconFile = new File( basedir, icon.getHref() );
            if ( !iconFile.exists() )
            {
                getLog().debug( "icon " + icon.getHref() + " not found at " + iconFile.getAbsolutePath() );
                iconFile = new File( basedir + "/src/jnlp/icons", icon.getHref() );
                if ( !iconFile.exists() )
                {
                    getLog().debug( "icon " + icon.getHref() + " not found at " + iconFile.getAbsolutePath() );
                    throw new MojoExecutionException( "icon: " + icon.getHref() + " doesn't exist." );
                }
            }
        }
        getLog().debug( "icon " + icon.getHref() + " found at " + iconFile.getAbsolutePath() );
        return iconFile;
    }

    /** return the number of signed jars **/
    private int signJars( File directory, FileFilter fileFilter )
        throws MojoExecutionException
    {

        File[] jarFiles = directory.listFiles( fileFilter );

        getLog().debug( "signJars in " + directory + " found " + jarFiles.length + " jar(s) to sign" );

        if ( jarFiles.length == 0 )
            return 0;

        JarSignMojo signJar = new JarSignMojo();
        signJar.setAlias( sign.getAlias() );
        signJar.setBasedir( basedir );
        signJar.setKeypass( sign.getKeypass() );
        signJar.setKeystore( sign.getKeystore() );
        signJar.setSigFile( sign.getSigfile() );
        signJar.setStorepass( sign.getStorepass() );
        signJar.setType( sign.getStoretype() );
        signJar.setVerbose( this.verbose );
        signJar.setWorkingDir( getWorkDirectory() );
        signJar.setVerify( sign.getVerify() );

        for ( int i = 0; i < jarFiles.length; i++ )
        {
            signJar.setJarPath( jarFiles[i] );
            // we don't change the jar name
            // signJar.setSignedJar( xx );
            long lastModified = jarFiles[i].lastModified();
            signJar.execute();
            jarFiles[i].setLastModified( lastModified );
        }

        return jarFiles.length;
    }

    private void checkInput()
        throws MojoExecutionException
    {

        getLog().debug( "a fact " + this.artifactFactory );
        getLog().debug( "a resol " + this.artifactResolver );
        getLog().debug( "basedir " + this.basedir );
        getLog().debug( "depend " + this.dependencies );
        getLog().debug( "gzip " + this.gzip );
        getLog().debug( "pack200 " + this.pack200 );
        getLog().debug( "project " + this.getProject() );
        getLog().debug( "zipArchiver " + this.zipArchiver );
        getLog().debug( "usejnlpservlet " + this.usejnlpservlet );
        getLog().debug( "verifyjar " + this.verifyjar );
        getLog().debug( "verbose " + this.verbose );

        if ( SystemUtils.JAVA_VERSION_FLOAT < 1.5f)
        {
            if ( pack200 )
            {
                throw new MojoExecutionException( "SDK 5.0 minimum when using pack200." );
            }
            if ( this.jnlp.getCodebase() == null )
            {
                throw new MojoExecutionException( "You didn't specify a codebase. $$codebase can only be used with the jnlp-servlet, which requires SDK 5.0." );
            }
        }

        // FIXME check that for each J2SE only one of href and autodownload are defined.

        // FIXME
        /*
        if ( !"pom".equals( getProject().getPackaging() ) ) {
            throw new MojoExecutionException( "'" + getProject().getPackaging() + "' packaging unsupported. Use 'pom'" );
        }
        */
    }

    /**
     * @return
     */
    public MavenProject getProject()
    {
        return project;
    }

    void setWorkDirectory( File workDirectory )
    {
        this.workDirectory = workDirectory;
    }

    void setVerbose( boolean verbose )
    {
        this.verbose = verbose;
    }


    public JnlpConfig getJnlp()
    {
        return jnlp;
    }

    public List getCopiedArtifacts()
    {
        return copiedArtifacts;
    }

    /*
    public Artifact getArtifactWithMainClass() {
        return artifactWithMainClass;
    }
    */

    public boolean isArtifactWithMainClass( Artifact artifact )
    {
        final boolean b = artifactWithMainClass.equals( artifact );
        getLog().debug( "compare " + artifactWithMainClass + " with " + artifact + ": " + b );
        return b;
    }

    public String getSpec()
    {
        // shouldn't we automatically identify the spec based on the features used in the spec?
        // also detect conflicts. If user specified 1.0 but uses a 1.5 feature we should fail in checkInput().
        if ( jnlp.getSpec() != null )
        {
            return jnlp.getSpec();
        }
        return "1.0+";
    }
}