package com.millennium.app.features.steamguard.ui

/** Adds the next-code button to Steam 3.10.11's CircleCodeScreen, inside its React layout. */
internal object SteamGuardNextCodeScript {
    fun source(): String = """
    function guardNextCodeRuntime(base, require) {
        var React = require(115);
        var Native = require(16);
        var Guard = require(1162);
        var Box = require(1836).Box;
        var Button = require(1830).Button;
        var Text = require(1589).Text;
        var AccountSelector = require(2416).GuardAccountSelector;
        var Gear = require(1833).IconGear;
        var useIsFocused = require(1366).useIsFocused;
        var Localize = require(1450).Localize;
        var period = 30000;

        function nextBoundary() {
            // Same Date.now() clock and 30-second periods as Steam's Guard store.
            return (Math.floor(Date.now() / period) + 1) * period;
        }

        function NextCodeButton(props) {
            var store = Guard.useSteamGuardStore();
            var focused = useIsFocused();
            var state = React.useState(null);
            var result = state[0];
            var setResult = state[1];
            var allowed = !!props.expiresAt && !!props.steamID;

            React.useEffect(function() {
                if (!allowed || !focused) {
                    setResult(null);
                    return;
                }
                var disposed = false;
                var sequence = 0;
                var timer = null;
                var appState = Native.AppState.currentState;

                function clear() {
                    ++sequence;
                    if (timer !== null) clearTimeout(timer);
                    timer = null;
                }

                function refresh() {
                    clear();
                    setResult(null);
                    if (disposed || (appState && appState !== 'active')) return;
                    // Prefer the circle's expiry. Recover from a suspended timer or clock change.
                    var boundary = props.expiresAt * 1000;
                    var now = Date.now();
                    if (boundary <= now || boundary > now + period) boundary = nextBoundary();
                    var request = sequence;
                    timer = setTimeout(refresh, Math.max(1, boundary - Date.now()));
                    try {
                        Promise.resolve(store.GetSteamGuardCodeForTime(props.steamID, boundary))
                            .then(function(value) {
                                if (disposed || request !== sequence || nextBoundary() !== boundary) return;
                                if (!value || !/^[23456789BCDFGHJKMNPQRTVWXY]{5}$/.test(value.code) ||
                                    value.rtExpiry * 1000 !== boundary + period) return;
                                setResult({ steamID: props.steamID, boundary: boundary, code: value.code });
                            }, function() {
                                // Keep the placeholder on failure; never invent a code or log secrets.
                            });
                    } catch (_) {
                        // Steam owns availability of the selected account's authenticator.
                    }
                }

                var subscription = Native.AppState.addEventListener('change', function(value) {
                    appState = value;
                    refresh();
                });
                refresh();
                return function() {
                    disposed = true;
                    clear();
                    subscription.remove();
                };
            }, [store, props.steamID, props.expiresAt, allowed, focused]);

            function usableCode() {
                return allowed && focused && result && result.steamID === props.steamID &&
                    result.boundary === nextBoundary() &&
                    (!Native.AppState.currentState || Native.AppState.currentState === 'active')
                    ? result.code : null;
            }

            var code = usableCode();
            var label = Localize('#Carousel_Next');
            // Steam's Simplified Chinese carousel label says "next page".
            if (label === '下一页') label = '下一个';
            if (!label || label === '#Carousel_Next') label = 'Next';
            var separator = label === '下一个' || label === '下一個' ? '：' : ': ';
            return React.createElement(Box, {
                flexGrow: 1, flexShrink: 1, paddingX: 'small', style: { minWidth: 0 }
            }, React.createElement(Button, {
                tone: 'info', size: 'small', width: 'full', disabled: !code,
                onPress: function() {
                    // A tap queued at the period boundary must not copy a stale preview.
                    var current = usableCode();
                    if (!current) return;
                    Native.Clipboard.setString(current);
                    Native.ToastAndroid.show(
                        Localize('#Guard_CopiedAlert').replace(/[!！]+$/, ''), Native.ToastAndroid.SHORT
                    );
                }
            }, React.createElement(Text, {
                size: 'small', weight: 'strong', align: 'center', numberOfLines: 1,
                adjustsFontSizeToFit: true, minimumFontScale: 0.65, style: { flexShrink: 1 }
            }, label + separator + (code || '-----'))));
        }

        function find(element, predicate, depth) {
            if (depth < 0 || !element) return null;
            if (Array.isArray(element)) {
                for (var i = 0; i < element.length; ++i) {
                    var match = find(element[i], predicate, depth);
                    if (match) return match;
                }
                return null;
            }
            if (!React.isValidElement(element)) return null;
            if (predicate(element)) return element;
            return find(element.props.children, predicate, depth - 1);
        }

        function augment(type, props) {
            // Match the complete token-page layout, not translated labels or screen coordinates.
            if (type !== Box || !props || props.flexDirection !== 'column' ||
                props.paddingX !== 'gutter' || typeof props.onLayout !== 'function' ||
                !Array.isArray(props.children) || props.children.length !== 4) return props;
            var children = props.children;
            var selector = find(children[0], function(node) { return node.type === AccountSelector; }, 3);
            var footer = children[2];
            if (!selector || !React.isValidElement(footer) || footer.type !== Box ||
                footer.props.flexDirection !== 'row' || footer.props.justifyContent !== 'spaceBetween' ||
                !Array.isArray(footer.props.children) || footer.props.children.length !== 2 ||
                !find(footer.props.children[1], function(node) { return node.type === Gear; }, 1)) return props;
            var meter = find(children[1], function(node) {
                return typeof node.props.expiresAt === 'number' && isFinite(node.props.expiresAt) &&
                    node.props.expiresAt > 0 && typeof node.props.size === 'number';
            }, 3);
            // The warning branch has no circular meter: keep the button disabled until dismissed.
            var button = React.createElement(NextCodeButton, {
                key: 'millennium-next-guard-code', steamID: selector.props.value,
                expiresAt: meter ? meter.props.expiresAt : 0
            });
            var updated = children.slice();
            updated[2] = React.cloneElement(footer, null, footer.props.children[0], button, footer.props.children[1]);
            return Object.assign({}, props, { children: updated });
        }

        function wrap(create) {
            return function(type, props, key) {
                var updated = props;
                try { updated = augment(type, props); } catch (_) { /* Retain Steam's original page. */ }
                return create.call(base, type, updated, key);
            };
        }

        // Only module 2413 receives this runtime. Preserve namespace exports without mutating React.
        var descriptors = Object.getOwnPropertyDescriptors(base);
        descriptors.jsx = { value: wrap(base.jsx), enumerable: true };
        descriptors.jsxs = { value: wrap(base.jsxs), enumerable: true };
        return Object.defineProperties({}, descriptors);
    }
""".trimIndent()
}
