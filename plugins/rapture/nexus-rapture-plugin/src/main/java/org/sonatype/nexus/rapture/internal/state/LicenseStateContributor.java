/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.rapture.internal.state;

import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.SystemStatus;
import org.sonatype.nexus.rapture.StateContributor;
import org.sonatype.sisu.goodies.common.ComponentSupport;

import com.google.common.collect.ImmutableMap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Contributes {@code license} state.
 *
 * @since 3.0
 */
@Named
@Singleton
public class LicenseStateContributor
  extends ComponentSupport
  implements StateContributor
{
  private static final String STATE_ID = "license";

  private final Provider<SystemStatus> systemStatus;

  @Inject
  public LicenseStateContributor(final Provider<SystemStatus> systemStatus) {
    this.systemStatus = checkNotNull(systemStatus);
  }

  @Nullable
  @Override
  public Map<String, Object> getState() {
    return ImmutableMap.of(STATE_ID, calculateLicense());
  }

  private Object calculateLicense() {
    SystemStatus status = systemStatus.get();
    LicenseXO result = new LicenseXO();
    result.setRequired(!"OSS".equals(status.getEditionShort()));
    result.setInstalled(status.isLicenseInstalled());
    return result;
  }
}
