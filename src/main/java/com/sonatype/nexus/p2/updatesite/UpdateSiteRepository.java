/**
 * Copyright (c) 2008-2011 Sonatype, Inc.
 *
 * All rights reserved. Includes the third-party code listed at http://www.sonatype.com/products/nexus/attributions.
 * Sonatype and Sonatype Nexus are trademarks of Sonatype, Inc. Apache Maven is a trademark of the Apache Foundation.
 * M2Eclipse is a trademark of the Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package com.sonatype.nexus.p2.updatesite;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.codehaus.tycho.model.Feature;
import org.codehaus.tycho.model.IFeatureRef;
import org.codehaus.tycho.model.PluginRef;
import org.codehaus.tycho.model.UpdateSite;
import org.codehaus.tycho.model.UpdateSite.FeatureRef;
import org.sonatype.nexus.configuration.Configurator;
import org.sonatype.nexus.configuration.model.CRepository;
import org.sonatype.nexus.configuration.model.CRepositoryExternalConfigurationHolderFactory;
import org.sonatype.nexus.proxy.IllegalOperationException;
import org.sonatype.nexus.proxy.ItemNotFoundException;
import org.sonatype.nexus.proxy.RemoteAccessException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.StorageException;
import org.sonatype.nexus.proxy.attributes.inspectors.DigestCalculatingInspector;
import org.sonatype.nexus.proxy.item.AbstractStorageItem;
import org.sonatype.nexus.proxy.item.DefaultStorageFileItem;
import org.sonatype.nexus.proxy.item.RepositoryItemUid;
import org.sonatype.nexus.proxy.item.StorageCollectionItem;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.item.uid.IsHiddenAttribute;
import org.sonatype.nexus.proxy.registry.ContentClass;
import org.sonatype.nexus.proxy.repository.AbstractProxyRepository;
import org.sonatype.nexus.proxy.repository.DefaultRepositoryKind;
import org.sonatype.nexus.proxy.repository.HostedRepository;
import org.sonatype.nexus.proxy.repository.MutableProxyRepositoryKind;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.repository.RepositoryKind;
import org.sonatype.nexus.proxy.storage.UnsupportedStorageOperationException;
import org.sonatype.nexus.proxy.storage.local.fs.DefaultFSLocalRepositoryStorage;
import org.sonatype.nexus.proxy.storage.local.fs.FileContentLocator;
import org.sonatype.nexus.proxy.walker.AbstractWalkerProcessor;
import org.sonatype.nexus.proxy.walker.DefaultWalkerContext;
import org.sonatype.nexus.proxy.walker.WalkerContext;
import org.sonatype.nexus.proxy.walker.WalkerFilter;
import org.sonatype.nexus.scheduling.NexusScheduler;

import com.sonatype.nexus.p2.P2Constants;
import com.sonatype.nexus.p2.P2ContentClass;
import com.sonatype.nexus.p2.facade.P2Facade;
import com.sonatype.nexus.p2.metadata.AbstractP2MetadataSource;
import com.sonatype.nexus.p2.util.P2Util;

@Component( role = Repository.class, hint = UpdateSiteRepository.ROLE_HINT, instantiationStrategy = "per-lookup", description = "Eclipse Update Site" )
public class UpdateSiteRepository
    extends AbstractProxyRepository
    implements Initializable, Repository
{
    private static final String DEFAULT_FEATURES_DIR = "features/";

    private static final String DEFAULT_PLUGINS_DIR = "plugins/";

    public static final String ROLE_HINT = "eclipse-update-site";

    @Requirement( hint = P2ContentClass.ID )
    private ContentClass contentClass;

    @Requirement
    private NexusScheduler scheduler;

    @Requirement
    private P2Facade p2;

    @Requirement( role = UpdateSiteRepositoryConfigurator.class )
    private UpdateSiteRepositoryConfigurator updateSiteRepositoryConfigurator;

    private MutableProxyRepositoryKind repositoryKind;

    private String overwriteRemoteUrl;

    @Override
    public void initialize()
        throws InitializationException
    {
        super.initialize();

        p2.initializeP2( P2Util.getPluginCoordinates() );
    }

    private static final WalkerFilter filter = new WalkerFilter()
    {
        @Override
        public boolean shouldProcess( final WalkerContext context, final StorageItem item )
        {
            return !item.getPath().startsWith( "." ) && !AbstractP2MetadataSource.isP2MetadataItem( item.getPath() );
        }

        @Override
        public boolean shouldProcessRecursively( final WalkerContext context, final StorageCollectionItem coll )
        {
            return shouldProcess( context, coll );
        }
    };

    @Override
    public ContentClass getRepositoryContentClass()
    {
        return contentClass;
    }

    @Override
    public RepositoryKind getRepositoryKind()
    {
        if ( repositoryKind == null )
        {
            repositoryKind =
                new MutableProxyRepositoryKind( this, null, new DefaultRepositoryKind( HostedRepository.class, null ),
                    new DefaultRepositoryKind( UpdateSiteRepository.class, null ) );
        }

        return repositoryKind;
    }

    @Override
    protected Configurator getConfigurator()
    {
        return updateSiteRepositoryConfigurator;
    }

    @Override
    protected CRepositoryExternalConfigurationHolderFactory<?> getExternalConfigurationHolderFactory()
    {
        return new CRepositoryExternalConfigurationHolderFactory<UpdateSiteRepositoryConfiguration>()
        {
            @Override
            public UpdateSiteRepositoryConfiguration createExternalConfigurationHolder( final CRepository config )
            {
                return new UpdateSiteRepositoryConfiguration( (Xpp3Dom) config.getExternalConfiguration() );
            }
        };
    }

    @Override
    protected UpdateSiteRepositoryConfiguration getExternalConfiguration( final boolean forModification )
    {
        return (UpdateSiteRepositoryConfiguration) super.getExternalConfiguration( forModification );
    }

    private void mirrorUpdateSite( final boolean force )
        throws StorageException, IllegalOperationException, UnsupportedStorageOperationException
    {
        UpdateSite site;
        try
        {
            final RepositoryItemUid siteUID = createUid( P2Constants.SITE_XML );
            String oldSha1 = null;

            final ResourceStoreRequest request = new ResourceStoreRequest( siteUID.getPath() );

            try
            {
                oldSha1 =
                    getLocalStorage().retrieveItem( this, request ).getAttributes().get(
                        DigestCalculatingInspector.DIGEST_SHA1_KEY );
            }
            catch ( final ItemNotFoundException e )
            {
                // it's okay
            }

            final StorageFileItem siteItem = (StorageFileItem) doRetrieveRemoteItem( request );

            if ( !force && oldSha1 != null
                && oldSha1.equals( siteItem.getAttributes().get( DigestCalculatingInspector.DIGEST_SHA1_KEY ) ) )
            {
                return;
            }
            site = UpdateSite.read( siteItem.getInputStream() );
        }
        catch ( final Exception e )
        {
            throw new StorageException( "Could not read site.xml", e );
        }

        final List<FeatureRef> features = site.getFeatures();

        getLogger().info( "Mirroring " + features.size() + " features from update site " + getName() );

        final Set<String> mirrored = new HashSet<String>();

        for ( final IFeatureRef feature : features )
        {
            mirrorFeature( site, feature, mirrored );
        }

        final ResourceStoreRequest root = new ResourceStoreRequest( RepositoryItemUid.PATH_ROOT );

        final DefaultWalkerContext ctx = new DefaultWalkerContext( this, root, filter );
        ctx.getContext().put( "mirrored", mirrored );
        ctx.getProcessors().add( new AbstractWalkerProcessor()
        {
            @SuppressWarnings( "unchecked" )
            @Override
            public void processItem( final WalkerContext context, final StorageItem item )
                throws Exception
            {
                final Set<String> mirrored = (Set<String>) context.getContext().get( "mirrored" );

                if ( item.getRepositoryItemUid().getBooleanAttributeValue( IsHiddenAttribute.class ) )
                {
                    return;
                }

                if ( item instanceof StorageFileItem && !mirrored.contains( item.getPath().substring( 1 ) ) )
                {
                    doDeleteItem( new ResourceStoreRequest( item.getPath() ) );
                }
            }
        } );
        getWalker().walk( ctx );

        getLogger().debug( "Generating P2 metadata for Eclipse Update Site " + getName() );

        final File baseDir = getLocalStorage().getBaseDir( this, root );
        final File metadataDir = new File( baseDir, ".p2" );

        try
        {
            FileUtils.deleteDirectory( metadataDir );
        }
        catch ( final IOException e )
        {
            getLogger().warn( "Unexpected IOException", e );
        }

        p2.generateSiteMetadata( baseDir, metadataDir, getName() );

        try
        {
            importFile( metadataDir, P2Constants.ARTIFACTS_PATH );
            importFile( metadataDir, P2Constants.CONTENT_PATH );
            FileUtils.deleteDirectory( metadataDir );
        }
        catch ( final IOException e )
        {
            // TODO this can actually happen on Windows
            getLogger().warn( "Unexpected IOException", e );
        }

    }

    private void importFile( final File baseDir, final String relPath )
        throws StorageException, UnsupportedStorageOperationException, IllegalOperationException
    {
        final File source = new File( baseDir, relPath );

        final ResourceStoreRequest request = new ResourceStoreRequest( relPath );

        final DefaultStorageFileItem file =
            new DefaultStorageFileItem( this, request, source.canRead(), source.canWrite(), new FileContentLocator(
                source, getMimeUtil().getMimeType( source ) ) );
        file.setModified( source.lastModified() );
        file.setCreated( source.lastModified() );
        file.setLength( source.length() );

        storeItem( false, file );
    }

    /**
     * will mirror a feature from remote site, supports features stored on remote server, or features stored on other
     * servers, via an absolute url in the site.xml
     * 
     * @param site
     * @param featureRef
     * @param mirrored
     * @throws StorageException
     * @throws IllegalOperationException
     */
    private void mirrorFeature( final UpdateSite site, final IFeatureRef featureRef, final Set<String> mirrored )
        throws StorageException, IllegalOperationException
    {
        final ResourceStoreRequest request = createResourceStoreRequest( featureRef );

        if ( request == null || !mirrored.add( request.getRequestPath() ) )
        {
            return;
        }

        getLogger().debug( "Mirroring feature " + featureRef );

        Feature feature = null;

        // request url will only be set when an absolute uri is found
        if ( request.getRequestUrl() != null )
        {
            final String absoluteUrl = request.getRequestUrl();
            request.setRequestUrl( null );

            feature = mirrorAbsoluteFeature( absoluteUrl, featureRef, request, mirrored );
        }
        else
        {
            feature = mirrorRelativeFeature( featureRef, request, mirrored );
        }

        if ( feature != null )
        {
            final List<PluginRef> includedPlugins = feature.getPlugins();
            final List<Feature.FeatureRef> includedFeatures = feature.getIncludedFeatures();

            getLogger().debug(
                featureRef + " includes " + includedFeatures.size() + " features and " + includedPlugins.size()
                    + " plugins" );

            for ( final PluginRef plugin : includedPlugins )
            {
                mirrorPlugin( site, plugin, mirrored );
            }

            for ( final IFeatureRef includedFeature : includedFeatures )
            {
                mirrorFeature( site, includedFeature, mirrored );
            }
        }
    }

    /**
     * Mirror an absolute feature, need to do a bit of magic here, since we can't simply use the proxy repo to retrieve
     * data Instead we must use remote storage to go off to the arbitrary url listed. Also, note that the file name
     * stored remotely may not be named to our standard ${id}_${version}.jar so we need to cache locally as that instead
     * of what is named remotely
     * 
     * @param absoluteUrl
     * @param featureRef
     * @param request
     * @param mirrored
     * @return
     */
    /**
     * @param absoluteUrl
     * @param featureRef
     * @param request
     * @param mirrored
     * @return
     */
    private Feature mirrorAbsoluteFeature( final String absoluteUrl, final IFeatureRef featureRef,
                                           final ResourceStoreRequest request, final Set<String> mirrored )
    {
        try
        {
            // we are building the path from these ids, so if not set, we have a problem
            if ( featureRef.getId() != null && featureRef.getVersion() != null )
            {
                // cerating update sites may not name there feature jars as we expect, ${id}_${version}.jar
                // if thats the case, download as they request, but store in local storage normalized, this
                // of course means updating the mirrored list so the file isn't deleted upon completion of mirror
                // process
                final ResourceStoreRequest defaultResource = generateResourceStoreRequest( featureRef );

                final File file =
                    mirrorAbsoluteItem( absoluteUrl, request, DEFAULT_FEATURES_DIR, defaultResource, mirrored );

                return Feature.readJar( file );
            }

            getLogger().warn( "Could not download feature " + featureRef + " referenced by update site " + getName() );
            return null;
        }
        catch ( final MalformedURLException e )
        {
            getLogger().warn( "Could not download feature " + featureRef + " referenced by update site " + getName(), e );
        }
        catch ( final IOException e )
        {
            getLogger().warn( "Could not download feature " + featureRef + " referenced by update site " + getName(), e );
        }
        catch ( final XmlPullParserException e )
        {
            getLogger().warn( "Could not download feature " + featureRef + " referenced by update site " + getName(), e );
        }
        catch ( final ItemNotFoundException e )
        {
            getLogger().warn( "Could not download feature " + featureRef + " referenced by update site " + getName(), e );
        }

        return null;
    }

    /**
     * Mirror a relative feature. Note that the file name stored remotely may not be named to our standard
     * ${id}_${version}.jar so we need to cache locally as that instead of what is named remotely
     * 
     * @param featureRef
     * @param request
     * @param mirrored
     * @return
     */
    private Feature mirrorRelativeFeature( final IFeatureRef featureRef, final ResourceStoreRequest request,
                                           final Set<String> mirrored )
    {
        try
        {
            final ResourceStoreRequest localRequest = generateResourceStoreRequest( featureRef );
            mirrorRelativeItem( request, localRequest, mirrored );

            final File file = getLocalStorage().getFileFromBase( this, localRequest );

            return Feature.readJar( file );
        }
        catch ( final Exception e )
        {
            getLogger().warn( "Could not download feature " + featureRef + " referenced by update site " + getName(), e );
        }

        return null;
    }

    /**
     * Mirror a plugin as listed in site.xml, will handle plugins that are stored on remote server, or are stored on
     * some arbirtray server via an absolute url listed for plugin path
     * 
     * @param site
     * @param pluginRef
     * @param mirrored
     */
    private void mirrorPlugin( final UpdateSite site, final PluginRef pluginRef, final Set<String> mirrored )
    {
        final ResourceStoreRequest request = createResourceStoreRequest( pluginRef );

        if ( !mirrored.add( request.getRequestPath() ) )
        {
            return;
        }

        getLogger().debug( "Mirroring plugin " + pluginRef );

        try
        {
            mirrorRelativeItem( request, null, mirrored );
        }
        catch ( final StorageException e )
        {
            getLogger().warn( "Could not download plugin " + pluginRef + " referenced by update site " + getName(), e );
        }
        catch ( final IllegalOperationException e )
        {
            getLogger().warn( "Could not download plugin " + pluginRef + " referenced by update site " + getName(), e );
        }
        catch ( final ItemNotFoundException e )
        {
            // if we can't find the relative url, try absolute
            if ( !mirrorAbsolutePlugin( pluginRef, site, request, mirrored ) )
            {
                // if that fails, we have problem
                getLogger().warn( "Could not download plugin " + pluginRef + " referenced by update site " + getName(),
                    e );
            }
        }
    }

    /**
     * Mirror an absolute plugin, stored on an absolute url, regardless of remote filename, will change local filename
     * to be normalized
     * 
     * @param pluginRef
     * @param site
     * @param request
     * @param mirrored
     * @return
     */
    private boolean mirrorAbsolutePlugin( final PluginRef pluginRef, final UpdateSite site,
                                          final ResourceStoreRequest request, final Set<String> mirrored )
    {
        try
        {
            // here we need to check the <archive> tags in the site, to find proper absoluteUrl
            final Map<String, String> archives = site.getArchives();

            final String absoluteUrl = archives.get( request.getRequestPath() );

            if ( absoluteUrl == null )
            {
                getLogger().warn( "archive url not set for plugin " + request.getRequestPath() );
                return false;
            }

            if ( !new URI( absoluteUrl ).isAbsolute() )
            {
                getLogger().warn(
                    "archive url (" + absoluteUrl + ") is not absolute for plugin " + request.getRequestPath() );
                return false;
            }

            mirrorAbsoluteItem( absoluteUrl.substring( 0, absoluteUrl.lastIndexOf( "/" ) ), request,
                DEFAULT_PLUGINS_DIR, createResourceStoreRequest( pluginRef ), mirrored );

            return true;
        }
        catch ( final URISyntaxException e )
        {
            getLogger().warn( "archive url has illegal syntax for plugin " + request.getRequestPath(), e );
        }
        catch ( final StorageException e )
        {
            getLogger().warn( "storage problem for plugin " + request.getRequestPath(), e );
        }
        catch ( final ItemNotFoundException e )
        {
            getLogger().warn( "item not found for plugin " + request.getRequestPath(), e );
        }

        return false;
    }

    private void mirrorRelativeItem( final ResourceStoreRequest remoteRequest, final ResourceStoreRequest localRequest,
                                     final Set<String> mirrored )
        throws StorageException, IllegalOperationException, ItemNotFoundException
    {
        final AbstractStorageItem item = (AbstractStorageItem) retrieveItem( true, remoteRequest );

        // cerating update sites may not name there jars as we expect,
        // if thats the case, download as they request, but store in local storage normalized, this
        // of course means updating the mirrored list so the file isn't deleted upon completion of mirror process
        if ( localRequest != null )
        {
            if ( !mirrored.contains( localRequest.getRequestPath() ) )
            {
                mirrored.remove( remoteRequest.getRequestPath() );
                mirrored.add( localRequest.getRequestPath() );
            }

            // update the uid
            item.setRepositoryItemUid( createUid( localRequest.getRequestPath() ) );

            // update the resource store request
            item.setResourceStoreRequest( localRequest );

            // cache locally
            doCacheItem( item );
        }
    }

    /**
     * Mirror an absolute item (plugin or feature) will retrieve from remote request, and store in local request
     * 
     * @param absoluteUrl
     * @param remoteStoreRequest
     * @param basePath
     * @param localStoreRequest
     * @return
     * @throws RemoteAccessException
     * @throws StorageException
     * @throws ItemNotFoundException
     */
    private File mirrorAbsoluteItem( final String absoluteUrl, final ResourceStoreRequest remoteStoreRequest,
                                     final String basePath, ResourceStoreRequest localStoreRequest,
                                     final Set<String> mirrored )
        throws RemoteAccessException, StorageException, ItemNotFoundException
    {
        // chop off the baseDir from the request, as we only care about the file in this case
        remoteStoreRequest.pushRequestPath( remoteStoreRequest.getRequestPath().substring( basePath.length() ) );

        // we need to chop it up here to remove filename from url, as that is in request
        final AbstractStorageItem item = getRemoteStorage().retrieveItem( this, remoteStoreRequest, absoluteUrl );

        // now put the basePath back on
        remoteStoreRequest.popRequestPath();

        if ( localStoreRequest == null )
        {
            localStoreRequest = remoteStoreRequest;
        }
        else
        {
            // update the list of what we have mirrored, since we have changed filename
            if ( !mirrored.contains( localStoreRequest.getRequestPath() ) )
            {
                mirrored.remove( remoteStoreRequest.getRequestPath() );
                mirrored.add( localStoreRequest.getRequestPath() );
            }

            // update the uid
            item.setRepositoryItemUid( createUid( localStoreRequest.getRequestPath() ) );

            // update the resource store request
            item.setResourceStoreRequest( localStoreRequest );
        }

        // cache locally
        doCacheItem( item );

        return getLocalStorage().getFileFromBase( this, localStoreRequest );
    }

    private ResourceStoreRequest createResourceStoreRequest( final PluginRef pluginRef )
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( DEFAULT_PLUGINS_DIR );
        sb.append( pluginRef.getId() ).append( "_" ).append( pluginRef.getVersion() ).append( ".jar" );
        return new ResourceStoreRequest( sb.toString() );
    }

    private ResourceStoreRequest createResourceStoreRequest( final IFeatureRef featureRef )
    {
        String urlStr = null;
        if ( featureRef instanceof UpdateSite.FeatureRef )
        {
            urlStr = ( (UpdateSite.FeatureRef) featureRef ).getUrl();
        }

        if ( urlStr != null )
        {
            try
            {
                // if absolute url, we will put the generated path ref into requestPath
                // and the real url in requestUrl. This ResourceStoreRequest won't be used
                // to request anything if absolute url is set (a new object will be created)
                if ( new URI( urlStr ).isAbsolute() )
                {
                    final ResourceStoreRequest request =
                        new ResourceStoreRequest( DEFAULT_FEATURES_DIR
                            + urlStr.substring( urlStr.lastIndexOf( "/" ) + 1 ) );

                    request.setRequestUrl( urlStr.substring( 0, urlStr.lastIndexOf( "/" ) ) );

                    return request;
                }
                else
                {
                    return new ResourceStoreRequest( urlStr );
                }
            }
            catch ( final URISyntaxException e )
            {
                getLogger().warn( "illegal url found in feature", e );
                return null;
            }
        }

        if ( featureRef.getId() != null && featureRef.getVersion() != null )
        {
            return generateResourceStoreRequest( featureRef );
        }

        getLogger().warn( "Could not determine referenced feature path " + featureRef.getDom() );
        return null;
    }

    private ResourceStoreRequest generateResourceStoreRequest( final IFeatureRef featureRef )
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( DEFAULT_FEATURES_DIR );
        sb.append( featureRef.getId() ).append( "_" ).append( featureRef.getVersion() ).append( ".jar" );
        return new ResourceStoreRequest( sb.toString() );
    }

    /**
     * Returns <code>true</code> if item exists in the local storage and is not old.
     */
    protected boolean isItemValid( final String path )
        throws StorageException
    {
        try
        {
            final AbstractStorageItem item = getLocalStorage().retrieveItem( this, new ResourceStoreRequest( path ) );

            return !isOld( item );
        }
        catch ( final ItemNotFoundException e )
        {
            return false;
        }
    }

    public void mirror( final boolean force )
    {
        final UpdateSiteMirrorTask task = scheduler.createTaskInstance( UpdateSiteMirrorTask.class );

        task.setRepositoryId( getId() );
        task.setForce( force );

        scheduler.submit( "Eclipse Update Site Mirror (" + getId() + ")", task );
    }

    /* package */void doMirror( final boolean force )
    {
        if ( force )
        {
            overwriteRemoteUrl = null;
        }

        try
        {
            mirrorUpdateSite( force );
            setExposed( true );
            getApplicationConfiguration().saveConfiguration();
        }
        catch ( final Exception e )
        {
            getLogger().error( "Could not mirror Eclipse Update Site", e );
        }
    }

    @Override
    protected StorageItem doRetrieveItem( final ResourceStoreRequest request )
        throws IllegalOperationException, ItemNotFoundException, StorageException
    {
        fixRemoteUrl( request );

        if ( P2Constants.SITE_XML.equals( request.getRequestPath() ) )
        {
            throw new ItemNotFoundException( request, this );
        }

        if ( AbstractP2MetadataSource.isP2MetadataItem( request.getRequestPath() ) )
        {
            if ( !isItemValid( P2Constants.SITE_XML ) )
            {
                mirror( false );
            }
        }

        return super.doRetrieveItem( request );
    }

    private void fixRemoteUrl( final ResourceStoreRequest request )
    {
        if ( overwriteRemoteUrl != null )
        {
            return;
        }

        if ( P2Constants.SITE_XML.equals( request.getRequestPath() ) )
        {
            return;
        }

        try
        {
            final RepositoryItemUid siteUID = createUid( P2Constants.SITE_XML );
            final ResourceStoreRequest siteRequest = new ResourceStoreRequest( siteUID.getPath() );
            StorageFileItem siteItem;
            try
            {
                siteItem = (StorageFileItem) getLocalStorage().retrieveItem( this, siteRequest );
            }
            catch ( final ItemNotFoundException e )
            {
                siteItem = (StorageFileItem) getRemoteStorage().retrieveItem( this, siteRequest, getRemoteUrl() );
            }

            final PlexusConfiguration plexusConfig =
                new XmlPlexusConfiguration( Xpp3DomBuilder.build( new InputStreamReader( siteItem.getInputStream() ) ) );

            overwriteRemoteUrl = plexusConfig.getAttribute( "url" );
            getLogger().info( "Remote update site does overwrite the remote url " + overwriteRemoteUrl );
        }
        catch ( final Exception e )
        {
            getLogger().debug( e.getMessage(), e );
            overwriteRemoteUrl = "";
        }
    }

    @Override
    public DefaultFSLocalRepositoryStorage getLocalStorage()
    {
        return (DefaultFSLocalRepositoryStorage) super.getLocalStorage();
    }

    public int getArtifactMaxAge()
    {
        return getExternalConfiguration( false ).getArtifactMaxAge();
    }

    public void setArtifactMaxAge( final int maxAge )
    {
        getExternalConfiguration( true ).setArtifactMaxAge( maxAge );
    }

    public int getMetadataMaxAge()
    {
        return getExternalConfiguration( false ).getMetadataMaxAge();
    }

    public void setMetadataMaxAge( final int metadataMaxAge )
    {
        getExternalConfiguration( true ).setMetadataMaxAge( metadataMaxAge );
    }

    @Override
    public boolean isOld( final StorageItem item )
    {
        if ( AbstractP2MetadataSource.isP2MetadataItem( item.getPath() ) )
        {
            return super.isOld( getMetadataMaxAge(), item );
        }
        else
        {
            return super.isOld( getArtifactMaxAge(), item );
        }
    }

    @Override
    public String getRemoteUrl()
    {
        if ( !StringUtils.isEmpty( overwriteRemoteUrl ) )
        {
            return overwriteRemoteUrl;
        }
        return super.getRemoteUrl();
    }

    @Override
    public void expireCaches( final ResourceStoreRequest request )
    {
        super.expireCaches( request );

        overwriteRemoteUrl = null;
    }
}
