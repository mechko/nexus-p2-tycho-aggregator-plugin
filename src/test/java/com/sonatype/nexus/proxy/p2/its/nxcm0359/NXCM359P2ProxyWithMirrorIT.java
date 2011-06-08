/**
 * Copyright (c) 2008-2011 Sonatype, Inc.
 *
 * All rights reserved. Includes the third-party code listed at http://www.sonatype.com/products/nexus/attributions.
 * Sonatype and Sonatype Nexus are trademarks of Sonatype, Inc. Apache Maven is a trademark of the Apache Foundation.
 * M2Eclipse is a trademark of the Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package com.sonatype.nexus.proxy.p2.its.nxcm0359;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;
import org.sonatype.nexus.test.utils.TestProperties;

import com.sonatype.nexus.proxy.p2.its.AbstractNexusProxyP2IntegrationIT;

public class NXCM359P2ProxyWithMirrorIT
    extends AbstractNexusProxyP2IntegrationIT
{
    public NXCM359P2ProxyWithMirrorIT()
    {
        super( "p2proxywithmirror" );
    }

    @Override
    public void startProxy()
        throws Exception
    {
        String proxyRepoBaseUrl = TestProperties.getString( "proxy.repo.base.url" );

        replaceInFile( "target/nexus/proxy-repo/p2repowithmirror/artifacts.xml", "${proxy-repo-base-url}",
                       proxyRepoBaseUrl );
        replaceInFile( "target/nexus/proxy-repo/p2repowithmirror/mirrors.xml", "${proxy-repo-base-url}",
                       proxyRepoBaseUrl );

        super.startProxy();
    }

    @Test
    public void testProxyWithMirror()
        throws Exception
    {
        String nexusTestRepoUrl = getNexusTestRepoUrl();

        File installDir = new File( "target/eclipse/nxcm0359" );

        installUsingP2( nexusTestRepoUrl, "com.sonatype.nexus.p2.its.feature.feature.group", installDir
            .getCanonicalPath() );

        File feature = new File( installDir, "features/com.sonatype.nexus.p2.its.feature_1.0.0" );
        Assert.assertTrue( feature.exists() && feature.isDirectory() );

        File bundle = new File( installDir, "plugins/com.sonatype.nexus.p2.its.bundle_1.0.0.jar" );
        Assert.assertTrue( bundle.canRead() );
    }
}
