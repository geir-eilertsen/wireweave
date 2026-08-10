package net.fjordomatic.application;

/**
 * Surfaces the running Fjord build version for display, so the operator always knows which
 * version is deployed.
 */
public interface GetAppVersionUseCase {

    String appVersion();
}
