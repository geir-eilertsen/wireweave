package net.fjordomatic.application;

import net.fjordomatic.domain.ReverseProxyRoute;

import java.util.List;

public interface GetReverseProxyRoutesUseCase {
    List<ReverseProxyRoute> getReverseProxyRoutes();
}
