/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2013 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */

package org.sonatype.nexus.yum.internal;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Collection;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.proxy.ItemNotFoundException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.access.AccessManager;
import org.sonatype.nexus.proxy.access.Action;
import org.sonatype.nexus.proxy.item.RepositoryItemUid;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.repository.ProxyRepository;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.walker.AbstractFileWalkerProcessor;
import org.sonatype.nexus.proxy.walker.DefaultWalkerContext;
import org.sonatype.nexus.proxy.walker.Walker;
import org.sonatype.nexus.proxy.walker.WalkerContext;
import org.sonatype.nexus.proxy.walker.WalkerException;
import org.sonatype.nexus.yum.Yum;
import org.sonatype.nexus.yum.YumProxy;
import org.sonatype.nexus.yum.YumRepository;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.inject.assistedinject.Assisted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 2.7
 */
@Named
public class YumProxyImpl
    implements YumProxy
{

  private static final Logger log = LoggerFactory.getLogger(YumProxyImpl.class);

  private final ProxyRepository repository;

  private final Walker walker;

  private final File baseDir;

  private final YumRepository yumRepository;

  @Inject
  public YumProxyImpl(final Walker walker,
                      final @Assisted ProxyRepository repository)
      throws MalformedURLException, URISyntaxException

  {
    this.walker = checkNotNull(walker);
    this.repository = checkNotNull(repository);
    this.baseDir = RepositoryUtils.getBaseDir(repository);
    this.yumRepository = new YumRepositoryImpl(baseDir, repository.getId(), null);
  }

  @Override
  public File getBaseDir() {
    return baseDir;
  }

  @Override
  public Repository getNexusRepository() {
    return repository;
  }

  @Override
  public YumRepository getYumRepository() {
    return yumRepository;
  }

  @Override
  public YumProxy syncRepoMD() throws Exception {
    StorageItem repomdItem = repository.retrieveItem(new ResourceStoreRequest(Yum.PATH_OF_REPOMD_XML));
    if (repomdItem instanceof StorageFileItem) {
      RepositoryItemUid repomdUid = repomdItem.getRepositoryItemUid();
      repomdUid.getLock().lock(Action.update);
      try (InputStream in = ((StorageFileItem) repomdItem).getInputStream()) {
        final RepoMD repomd = new RepoMD(in);

        retrieveReferenced(repomd);
        removeObsolete(repomd);
      }
      finally {
        repomdUid.getLock().unlock();
      }
    }

    return this;
  }

  private void removeObsolete(final RepoMD repomd) {
    try {
      log.trace("Cleaning up Yum metadata from {}:/{}", repository.getId(), Yum.PATH_OF_REPODATA);
      final Collection<String> locations = Collections2.transform(repomd.getLocations(), new Function<String, String>()
      {
        @Nullable
        @Override
        public String apply(@Nullable final String input) {
          return "/" + input;
        }
      });
      final ResourceStoreRequest wrequest = new ResourceStoreRequest(
          RepositoryItemUid.PATH_ROOT + Yum.PATH_OF_REPODATA
      );
      wrequest.getRequestContext().put(AccessManager.REQUEST_AUTHORIZED, Boolean.TRUE);
      final DefaultWalkerContext wcontext = new DefaultWalkerContext(repository, wrequest);
      wcontext.getProcessors().add(new AbstractFileWalkerProcessor()
      {
        @Override
        protected void processFileItem(final WalkerContext context, final StorageFileItem fItem) {
          if (!fItem.getName().equals(Yum.NAME_OF_REPOMD_XML) && !locations.contains(fItem.getPath())) {
            try {
              context.getRepository().deleteItem(true, fItem.getResourceStoreRequest());
            }
            catch (Exception e) {
              log.warn(
                  "Failed to clean proxy YUM metadata due to {}/{}",
                  e.getClass().getName(), e.getMessage(), log.isDebugEnabled() ? e : null
              );
            }
          }
        }
      });
      walker.walk(wcontext);
    }
    catch (WalkerException e) {
      Throwable stopCause = e.getWalkerContext().getStopCause();
      if (!(stopCause instanceof ItemNotFoundException)) {
        if (stopCause != null) {
          log.warn(
              "Failed to clean proxy YUM metadata due to {}/{}",
              stopCause.getClass().getName(), stopCause.getMessage(), log.isDebugEnabled() ? e : null
          );
        }
        else {
          log.warn(
              "Failed to clean proxy YUM metadata due to {}/{}",
              e.getClass().getName(), e.getMessage(), log.isDebugEnabled() ? e : null
          );
        }
      }
    }
  }

  private void retrieveReferenced(final RepoMD repomd)
      throws Exception
  {
    for (final String location : repomd.getLocations()) {
      log.trace("Retrieving {}:{}", repository.getId(), "/" + location);
      repository.retrieveItem(new ResourceStoreRequest("/" + location));
    }
  }

}
