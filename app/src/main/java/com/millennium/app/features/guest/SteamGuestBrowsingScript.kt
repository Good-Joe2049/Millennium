package com.millennium.app.features.guest

import com.millennium.app.features.steamguard.ui.SteamGuardNextCodeScript
import com.millennium.app.features.library.SteamLibraryGridScript

/** Small, bundle-specific adapters. Account state and the native login form remain Steam-owned. */
internal object SteamGuestBrowsingScript {
    fun source(defaultRoute: String, nextCodeEnabled: Boolean, libraryPinchZoomEnabled: Boolean): String {
        return """
(function(g) {
    'use strict';
    if (g.__millenniumGuestInstalled) return;
    g.__millenniumGuestInstalled = true;

    function facade(base, name, value) {
        var result = Object.create(base);
        Object.defineProperty(result, name, { value: value, enumerable: true });
        return result;
    }

    function guestNavigation(base, require) {
        return facade(base, 'useNavigation', function() {
            var navigation = base.useNavigation();
            return require(115).useMemo(function() {
                return facade(navigation, 'navigate', function(name, params) {
                    // Suppress the startup prompt only when there is no active account.
                    // Existing-account reauthentication and explicit login stay native.
                    if (name === 'Modal' && params && params.screen === 'Login' &&
                        params.params && params.params.bCanDismiss === false) {
                        var state = require(1136).useDebugAuthState().GetCurrentState();
                        if (state.bReady && !state.bLoggedIn && !state.activeSteamID) return;
                    }
                    return navigation.navigate.apply(navigation, arguments);
                });
            }, [navigation]);
        });
    }

    function guestHome(base, require) {
        return facade(base, 'useHomeTab', function() {
            var result = base.useHomeTab();
            var auth = require(1136).useAuth();
            // Wait for restoration: a saved account must not be mistaken for a guest.
            if (!auth.bReady) {
                return [Object.assign({}, result[0], { isLoading: true }), result[1]];
            }
            if (!auth.bLoggedIn) {
                return [Object.assign({}, result[0], { data: 'store' }), result[1]];
            }
            return result;
        });
    }

    function guestMenu(base, require) {
        var React = require(115);
        return facade(base, 'ScreenWithHeader', function GuestMenuHeader(props) {
            var auth = require(1136).useAuth();
            var scroll = props.children;
            if (auth.bReady && !auth.bLoggedIn && React.isValidElement(scroll)) {
                var label = require(1450).Localize('#MobileLogin_SignIn');
                var row = React.createElement(require(1590).Box, {
                    key: 'millennium-guest-sign-in',
                    paddingX: 'gutter', paddingY: 'large',
                    accessibilityRole: 'button', accessibilityLabel: label,
                    onPress: function() {
                        props.navigation.navigate('Modal', {
                            screen: 'Login',
                            params: { bCanDismiss: true, bMakeActiveAccount: true }
                        });
                    }
                }, React.createElement(require(1589).Text, { weight: 'strong', tone: 'info' }, label));
                scroll = React.cloneElement(scroll, null, row, scroll.props.children);
            }
            return React.createElement(base.ScreenWithHeader, props, scroll);
        });
    }

    function guestSettingsQuery(base, require) {
        var guestSettings = { settings: { is_enabled: false }, mapAppsAllowed: new Map() };
        return facade(base, 'useQueryFromAsyncStorage', function(key) {
            var result = base.useQueryFromAsyncStorage.apply(base, arguments);
            var auth = require(1136).useAuth();
            // No account has no parental policy. Steam otherwise treats the absent result
            // as loading/blocked, hiding MainTabs and disabling public navigation.
            // Never change a stored query or the policy of an active account.
            if (key[0] === 'ParentalSettings_2' && auth.bReady &&
                !auth.bLoggedIn && !auth.activeSteamID) {
                return Object.assign({}, result, {
                    isLoading: false, data: guestSettings
                });
            }
            return result;
        });
    }

    function publicStoreUrl(value) {
        if (typeof value !== 'string' || /[\s\\]/.test(value)) return null;
        var mobile = /^steammobile:\/\/store\?(.*)/i.exec(value);
        if (mobile) {
            var app = /(?:^|&)appid=([1-9][0-9]*)(?:&|$)/.exec(mobile[1]);
            return app ? 'https://store.steampowered.com/app/' + app[1] + '/' : null;
        }
        var match = /^https:\/\/store\.steampowered\.com(\/[^?#]*)?([?#].*)?$/i.exec(value);
        if (!match) return null;
        var path = match[1] || '/';
        if (/(?:^|\/)\.{1,2}(?:\/|$)|%(?:2e|2f|5c|25)/i.test(path)) return null;
        // Only public browsing destinations. Account, checkout and authentication links
        // continue through Steam's original handler, including Steam Guard links.
        if (path === '/' || /^\/(?:app\/[0-9]+|sub\/[0-9]+|bundle\/[0-9]+|news|search|explore|genre|category|tags|publisher|developer|sale|specials)(?:\/|$)/i.test(path)) {
            return 'https://store.steampowered.com' + path + (match[2] || '');
        }
        return null;
    }

    function guestLinks(exports, require) {
        var original = exports.useLinkNavigationHandling;
        exports.useLinkNavigationHandling = function() {
            original();
            var React = require(115);
            var auth = require(1136).useAuth();
            var store = require(1136).useDebugAuthState();
            var open = exports.useOpenUrl();
            React.useEffect(function() {
                if (!auth.bReady || auth.bLoggedIn) return;
                var box = exports.AppLinkWithNoActiveUser;
                var previous = box.set;
                function set(value) {
                    var state = store.GetCurrentState();
                    var url = state.bReady && !state.bLoggedIn ? publicStoreUrl(value) : null;
                    if (url) {
                        open(url);
                        return previous.call(box, '');
                    }
                    return previous.call(box, value);
                }
                box.set = set;
                var pending = box.get();
                if (pending) set(pending);
                return function() { if (box.set === set) box.set = previous; };
            }, [auth.bReady, auth.bLoggedIn, store, open]);
        };
    }

${SteamGuardDefaultPageScript.source(defaultRoute)}
${if (nextCodeEnabled) SteamGuardNextCodeScript.source() else ""}
${if (libraryPinchZoomEnabled) SteamLibraryGridScript.source() else ""}

    var targets = { 1136: true, 1476: true, 2125: true, 2135: true, 2412: true, 2455: true };
    if ($nextCodeEnabled) targets[2413] = true;
    if ($libraryPinchZoomEnabled) targets[3466] = true;
    var remaining = Object.keys(targets).length;
    var define;
    function wrapDefine(originalDefine) {
        return function(factory, id, dependencies) {
            if (!targets[id]) return originalDefine.apply(this, arguments);
            delete targets[id];
            var wrapped = function() {
                var args = Array.prototype.slice.call(arguments);
                var require = args[1];
                var cache = {};
                args[1] = function(dependency) {
                    if (Object.prototype.hasOwnProperty.call(cache, dependency)) return cache[dependency];
                    var value = require(dependency);
                    if (id === 1136 && dependency === 1366) value = guestNavigation(value, require);
                    if (id === 1476 && dependency === 3459) value = guestHome(value, require);
                    if (id === 2125 && dependency === 2126) value = guestSettingsQuery(value, require);
                    if (id === 2412 && dependency === 1477) value = guardNavigator(value, require);
                    if (id === 2413 && dependency === 263) value = guardNextCodeRuntime(value, require);
                    if (id === 2455 && dependency === 2352) value = guestMenu(value, require);
                    if ($libraryPinchZoomEnabled && id === 3466 && dependency === 263) value = libraryGridRuntime(value, require);
                    cache[dependency] = value;
                    return value;
                };
                Object.assign(args[1], require);
                var result = factory.apply(this, args);
                if (id === 2135) guestLinks(args[4].exports, require);
                return result;
            };
            var result = originalDefine.call(this, wrapped, id, dependencies);
            if (--remaining === 0) {
                // The adapters are now registered. All remaining Metro definitions are untouched.
                Object.defineProperty(g, '__d', { value: originalDefine, writable: true, configurable: true });
            }
            return result;
        };
    }
    Object.defineProperty(g, '__d', {
        configurable: true,
        get: function() { return define; },
        set: function(value) { define = wrapDefine(value); }
    });
})(globalThis);
""".trimIndent()
    }
}
