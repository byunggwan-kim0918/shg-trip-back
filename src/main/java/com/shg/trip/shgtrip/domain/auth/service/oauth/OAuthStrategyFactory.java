package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class OAuthStrategyFactory {

    private final Map<OAuthProvider, OAuthProviderStrategy> strategyMap;

    public OAuthStrategyFactory(List<OAuthProviderStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(OAuthProviderStrategy::getProvider, Function.identity()));
    }

    public OAuthProviderStrategy getStrategy(OAuthProvider provider) {
        OAuthProviderStrategy strategy = strategyMap.get(provider);
        if (strategy == null) {
            throw new BusinessException(ErrorCode.INVALID_PROVIDER);
        }
        return strategy;
    }
}
