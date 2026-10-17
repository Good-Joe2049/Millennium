package com.millennium.app.features.library

/** Scoped adapter for Steam 3.10.11's library; Steam retains data, portraits and navigation. */
internal object SteamLibraryGridScript {
    fun source(): String = """
${SteamLibraryGridGestureScript.source()}
${SteamLibraryGridTransitionScript.source()}

    function libraryGridRuntime(base, require) {
        var React, Native, Handlers, Storage, Grid;
        if (!${SteamLibraryGridDrawHook.available}) {
            return base;
        }
        try {
            React = require(115);
            Native = require(16);
            Handlers = require(563);
            Storage = require(1137).default;
            if (!Handlers.PinchGestureHandler || !Handlers.NativeViewGestureHandler ||
                !Native.Animated || !Native.ScrollView || !Native.RefreshControl || !Storage.getItem || !Storage.setItem) {
                return base;
            }
            Grid = libraryGridTransitions(React, Native);
        } catch (error) {
            return base;
        }
        var Animated = Native.Animated;
        var AnimatedScroll = Animated.createAnimatedComponent(Native.ScrollView);
        var useIsFocused = require(1366).useIsFocused;
        var storageKey = 'millennium.library.columns.v1';
        var density = { columns: 3, ready: false, revision: 0, listeners: new Set(), write: Promise.resolve() };
        function notifyDensity() { density.listeners.forEach(function(listener) { listener(); }); }
        var readRevision = density.revision;
        // Start the local preference read when the library module loads, before its screen mounts.
        // The timeout is only failure recovery; it never delays a successful read or a gesture.
        var readTimeout = setTimeout(function() {
            if (!density.ready) {
                density.ready = true; notifyDensity();
            }
        }, 1500);
        Promise.resolve().then(function() {
            return Storage.getItem(storageKey);
        }).then(function(value) {
            var columns = Number(value);
            if (!density.ready && readRevision === density.revision && Grid.stops.indexOf(columns) >= 0) density.columns = columns;
        }, function() {}).then(function() {
            clearTimeout(readTimeout);
            density.ready = true;
            notifyDensity();
        });
        function saveDensity(columns) {
            density.columns = columns;
            density.revision++;
            density.write = density.write.catch(function() {}).then(function() {
                return Storage.setItem(storageKey, String(columns));
            }).catch(function() {});
        }
        function element(value) {
            return typeof value === 'function' ? React.createElement(value) : value;
        }
        function sameGames(left, right) {
            return left.length === right.length && left.every(function(item, index) {
                return item.appid === right[index].appid;
            });
        }
        function LibraryGrid(props) {
            var focused = useIsFocused();
            var list = React.useRef(null), pinch = React.useRef(null), scrollHandler = React.useRef(null);
            var scrollY = React.useRef(null);
            if (!scrollY.current) scrollY.current = new Animated.Value(0);
            var runtime = React.useRef(null);
            if (!runtime.current) runtime.current = {
                columns: density.columns, ready: density.ready, width: 0, height: 0, header: 0, footer: 0,
                offset: 0, windowOffset: 0, content: 0, gridHeight: 0, revision: 0, generation: 0,
                session: null, visual: null, pending: null, settling: false, animation: null,
                watchdog: null, frame: null, pool: new Map(), mounted: true, reduceMotion: false, focused: focused
            };
            var r = runtime.current;
            r.props = props;
            r.focused = focused;
            var state = React.useState(0), setRevision = state[1];
            function publish() {
                if (r.mounted) setRevision(function(value) { return value + 1; });
            }
            function updateGestureBounds() {
                var top = -Math.min(r.height, Math.max(0, r.header - r.offset));
                if (!r.session && top !== r.gestureTop && pinch.current && pinch.current.setNativeProps) {
                    r.gestureTop = top;
                    pinch.current.setNativeProps({ hitSlop: { top: top } });
                }
            }
            function clearPending() {
                if (r.watchdog !== null) clearTimeout(r.watchdog);
                if (r.frame !== null) cancelAnimationFrame(r.frame);
                r.watchdog = r.frame = null;
                r.pending = null;
            }
            function abort() {
                r.generation++;
                if (r.animation) r.animation.stop();
                r.animation = null;
                clearPending();
                if (r.gesture) r.gesture.reset();
                r.session = r.visual = null;
                r.windowOffset = r.offset;
                r.settling = false;
                publish();
            }
            function reveal() {
                clearPending();
                r.session = r.visual = null;
                r.windowOffset = r.offset;
                r.settling = false;
                saveDensity(r.columns);
                publish();
            }
            function handoffReady() {
                var pending = r.pending;
                if (!pending) return false;
                var layout = Grid.metrics(r.width, r.columns);
                var gridHeight = Grid.contentHeight(layout, r.session.data.length, 0);
                var expected = r.header + gridHeight + r.footer;
                if (Math.abs(r.gridHeight - gridHeight) > 2) return false;
                if (Math.abs(r.content - expected) > 2 &&
                    Math.abs(r.content - Math.max(expected, r.height)) > 2) return false;
                if (Math.abs(r.offset - pending.offset) > 1) {
                    if (list.current) list.current.scrollTo({ y: pending.offset, animated: false });
                    return false;
                }
                return true;
            }
            function tryHandoff() {
                if (!r.pending || r.frame !== null || !handoffReady()) return;
                var generation = r.generation;
                r.frame = requestAnimationFrame(function() {
                    r.frame = null;
                    if (generation === r.generation && r.pending && handoffReady()) reveal();
                });
            }
            function finish() {
                if (!r.session) return;
                var session = r.session;
                r.settling = false;
                if (r.columns === session.columns && Math.abs(r.offset - session.offset) < 1) { reveal(); return; }
                r.columns = session.columns;
                r.visual = Grid.still(session);
                r.pending = { offset: session.offset };
                r.revision++;
                // The same images stay mounted. Only acknowledge the scroll extent and offset;
                // there are no replacement rows or image requests to wait for.
                r.watchdog = setTimeout(function() {
                    if (r.pending) {
                        abort();
                    }
                }, 1500);
                publish();
            }
            function completeStage(commit) {
                if (!r.session || !r.visual) return;
                if (commit) {
                    r.session.columns = r.visual.to.columns;
                    r.session.offset = r.visual.toOffset;
                    r.session.anchor.top = Grid.box(r.visual.to, r.session.anchor.index,
                        r.session.header, r.session.offset).y;
                }
                r.visual = Grid.still(r.session);
                publish();
            }
            r.callbacks = {
                begin: function(event) {
                    if (!r.mounted || !r.focused || !r.ready || r.session || r.props.refreshing ||
                        r.width <= 24 || r.height <= 0 || !r.props.data.length ||
                        !Number.isFinite(event.focalX) || !Number.isFinite(event.focalY)) return false;
                    var selected = Grid.anchor(Grid.metrics(r.width, r.columns), r.props.data.length,
                        r.header, r.offset, event.focalX, event.focalY);
                    if (!selected) return false;
                    r.generation++;
                    r.session = { columns: r.columns, offset: Math.max(0, r.offset), width: r.width,
                        height: r.height, header: r.header, anchor: selected,
                        data: r.props.data, renderItem: r.props.renderItem, props: r.props,
                        pinned: new Set(r.pool.keys()) };
                    r.visual = Grid.still(r.session);
                    publish();
                    return true;
                },
                stage: function(direction, start, end) {
                    if (!r.session) return false;
                    var columns = Grid.adjacent(r.session.columns, direction);
                    if (columns === r.session.columns) return false;
                    var increasing = end > start;
                    var progress = r.gesture.scale.interpolate({
                        inputRange: increasing ? [start, end] : [end, start],
                        outputRange: increasing ? [0, 1] : [1, 0], extrapolate: 'clamp'
                    });
                    r.visual = Grid.stage(r.session, columns, progress);
                    publish();
                    return true;
                },
                completeStage: completeStage,
                settle: function(commit, progress) {
                    if (!r.session || !r.visual) return;
                    var generation = r.generation;
                    var value = new Animated.Value(progress);
                    r.visual = Object.assign({}, r.visual, { progress: value });
                    r.settling = true;
                    publish();
                    r.animation = Animated.timing(value, {
                        toValue: commit ? 1 : 0, duration: r.reduceMotion ? 0 : 200,
                        easing: Native.Easing.bezier(0.25, 0.1, 0.25, 1),
                        useNativeDriver: true, isInteraction: false
                    });
                    r.animation.start(function(result) {
                        if (!result.finished || !r.mounted || generation !== r.generation) return;
                        r.animation = null;
                        completeStage(commit);
                        finish();
                    });
                },
                finish: finish
            };
            if (!r.gesture) {
                var callbacks = {};
                ['begin', 'stage', 'completeStage', 'settle', 'finish'].forEach(function(name) {
                    callbacks[name] = function() { return r.callbacks[name].apply(null, arguments); };
                });
                r.gesture = libraryGridGesture(Native, Handlers, callbacks);
            }
            React.useEffect(function() {
                r.mounted = true;
                function loaded() {
                    if (!r.ready) { r.ready = density.ready; r.columns = density.columns; publish(); }
                }
                density.listeners.add(loaded);
                loaded();
                var appState = Native.AppState.addEventListener('change', function(value) {
                    if (value !== 'active') abort();
                });
                var reduceMotion = Native.AccessibilityInfo.addEventListener('reduceMotionChanged', function(value) {
                    r.reduceMotion = value;
                });
                Native.AccessibilityInfo.isReduceMotionEnabled().then(function(value) {
                    if (r.mounted) r.reduceMotion = value;
                }, function() {});
                return function() {
                    r.mounted = false;
                    density.listeners.delete(loaded);
                    appState.remove();
                    reduceMotion.remove();
                    abort();
                    r.pool.clear();
                };
            }, []);
            React.useEffect(function() { if (!focused && r.session) abort(); }, [focused]);
            React.useEffect(function() {
                if (r.session && (props.refreshing || !sameGames(r.session.data, props.data))) abort();
            }, [props.data, props.refreshing]);
            React.useLayoutEffect(function() { tryHandoff(); }, [r.revision]);

            var original = r.session ? r.session.props : props;
            var games = r.session ? r.session.data : props.data;
            var indices = React.useMemo(function() {
                var result = new Map();
                games.forEach(function(item, index) { result.set(String(item.appid), index); });
                return result;
            }, [games]);
            var layout = Grid.metrics(Math.max(25, r.width), r.columns);
            var visual = r.visual, locked = !!r.session;
            var mapping = React.useMemo(function() { return Grid.plan(visual); }, [visual]);
            var gridHeight = Grid.contentHeight(layout, games.length, 0);
            var required = new Set();
            function include(values) { values.forEach(function(index) { required.add(index); }); }
            if (r.ready && r.width > 24 && r.height > 0) {
                if (visual) {
                    include(Grid.windowIndices(visual.from, games.length, r.header,
                        visual.fromOffset, r.height, r.height / 4));
                    include(Grid.windowIndices(visual.to, games.length, r.header,
                        visual.toOffset, r.height, r.height / 4));
                    if (mapping) mapping.incoming.forEach(function(cell) { required.add(cell.target); });
                } else {
                    var offset = Grid.clamp(r.windowOffset, 0, Math.max(0, r.header + gridHeight - r.height));
                    include(Grid.windowIndices(layout, games.length, r.header, offset, r.height, r.height / 4));
                    // Warm only the adjacent denser viewport, not all games or all six densities.
                    var next = Grid.metrics(r.width, Grid.adjacent(r.columns, 1));
                    var anchor = Grid.anchor(layout, games.length, r.header, offset, r.width / 2, r.height / 2);
                    if (anchor && next.columns !== layout.columns) {
                        include(Grid.visible(next, games.length, r.header,
                            Grid.anchoredOffset(next, games.length, r.header, r.height, anchor), r.height));
                    }
                }
            }
            var dense = Grid.metrics(Math.max(25, r.width), 9);
            var limit = (Math.ceil(r.height * 1.5 / dense.stride) + 4) * 9;
            var baseWidth = Grid.metrics(Math.max(25, r.width), 2).cell;
            var covers = Grid.retain(r.pool, games, indices, required,
                r.session && r.session.pinned, baseWidth, limit);
            var headerTransform = visual ? [{ translateY: Animated.add(scrollY.current,
                Grid.interpolate(visual.progress, -visual.fromOffset, -visual.toOffset)) }] : [];
            var header = React.createElement(Animated.View, {
                key: 'header', pointerEvents: locked ? 'none' : 'auto',
                style: [original.ListHeaderComponentStyle, { paddingHorizontal: 12, transform: headerTransform }],
                onLayout: function(event) {
                    var height = event.nativeEvent.layout.height;
                    if (Math.abs(r.header - height) > 0.5) {
                        if (r.session) abort();
                        r.header = height;
                        updateGestureBounds();
                        publish();
                    }
                }
            }, element(original.ListHeaderComponent));
            var onScroll = React.useMemo(function() {
                return Animated.event([{ nativeEvent: { contentOffset: { y: scrollY.current } } }], {
                    useNativeDriver: true,
                    listener: function(event) {
                        r.offset = event.nativeEvent.contentOffset.y;
                        updateGestureBounds();
                        tryHandoff();
                        // Native scrolling moves existing cells. React only updates the bounded
                        // render window after crossing part of its overscan, never for every pixel.
                        if (!r.session && Math.abs(r.offset - r.windowOffset) > Math.max(1, r.height / 8)) {
                            r.windowOffset = r.offset;
                            publish();
                        }
                        if (r.props.onScroll) r.props.onScroll(event);
                    }
                });
            }, []);
            var scrollProps = Object.assign({}, original);
            ['data', 'numColumns', 'renderItem', 'keyExtractor', 'extraData', 'getItemLayout',
                'ListHeaderComponent', 'ListFooterComponent', 'ListEmptyComponent', 'ItemSeparatorComponent',
                'ListHeaderComponentStyle', 'ListFooterComponentStyle',
                'columnWrapperStyle', 'initialNumToRender', 'maxToRenderPerBatch', 'updateCellsBatchingPeriod',
                'windowSize', 'initialScrollIndex', 'onViewableItemsChanged', 'viewabilityConfig',
                'viewabilityConfigCallbackPairs', 'onEndReached', 'onEndReachedThreshold', 'onRefresh',
                'refreshing'].forEach(function(name) { delete scrollProps[name]; });
            Object.assign(scrollProps, {
                testID: 'millennium.library.grid.scroll.v1', ref: list,
                style: { flex: 1 }, contentContainerStyle: { flexGrow: 1 },
                scrollEnabled: !locked && original.scrollEnabled !== false,
                showsVerticalScrollIndicator: !locked && original.showsVerticalScrollIndicator !== false,
                removeClippedSubviews: false, onScroll: onScroll, scrollEventThrottle: 16,
                onContentSizeChange: function(width, height) {
                    r.content = height;
                    tryHandoff();
                    if (original.onContentSizeChange) original.onContentSizeChange(width, height);
                }
            });
            if (React.isValidElement(original.refreshControl)) {
                scrollProps.refreshControl = React.cloneElement(original.refreshControl,
                    { enabled: !locked && original.refreshControl.props.enabled !== false });
            } else if (original.onRefresh) {
                scrollProps.refreshControl = React.createElement(Native.RefreshControl, {
                    refreshing: !!original.refreshing, onRefresh: original.onRefresh,
                    enabled: !locked, progressViewOffset: original.progressViewOffset
                });
            }
            return React.createElement(Handlers.PinchGestureHandler, {
                ref: pinch, enabled: focused && r.ready && games.length > 0 &&
                    !props.refreshing && !r.settling && !r.pending,
                simultaneousHandlers: scrollHandler, shouldCancelWhenOutside: false,
                hitSlop: { top: -Math.min(r.height, Math.max(0, r.header - r.offset)) },
                onGestureEvent: r.gesture.event, onHandlerStateChange: r.gesture.state
            }, React.createElement(Animated.View, {
                collapsable: false, testID: 'millennium.library.grid.pinch.v1',
                style: [props.style, { flex: 1, paddingLeft: 0, paddingRight: 0, overflow: 'hidden' }],
                onLayout: function(event) {
                    var size = event.nativeEvent.layout;
                    if (Math.abs(size.width - r.width) > 0.5 || Math.abs(size.height - r.height) > 0.5) {
                        if (r.session) abort();
                        r.width = size.width; r.height = size.height;
                        publish();
                    }
                }
            }, React.createElement(Handlers.NativeViewGestureHandler, {
                ref: scrollHandler, simultaneousHandlers: pinch, disallowInterruption: false
            }, React.createElement(AnimatedScroll, scrollProps, header,
                React.createElement(Native.View, {
                    key: 'covers', testID: 'millennium.library.grid.covers.v1', collapsable: false,
                    pointerEvents: locked ? 'none' : 'box-none',
                    accessibilityElementsHidden: locked,
                    importantForAccessibility: locked ? 'no-hide-descendants' : 'auto',
                    style: { height: gridHeight },
                    onLayout: function(event) { r.gridHeight = event.nativeEvent.layout.height; tryHandoff(); }
                }, covers.map(function(entry) {
                    return React.createElement(Animated.View, {
                        key: entry.id, pointerEvents: entry.visible && !locked ? 'auto' : 'none',
                        accessibilityElementsHidden: !entry.visible,
                        importantForAccessibility: entry.visible ? 'auto' : 'no-hide-descendants',
                        style: { position: 'absolute', left: 0, top: 0,
                            width: entry.baseWidth, height: entry.baseWidth * 1.5,
                            opacity: Grid.opacity(entry.index, entry.visible, mapping),
                            transform: Grid.transform(layout, entry.index, entry.baseWidth, visual, scrollY.current, mapping) }
                    }, React.createElement(Native.View, {
                        collapsable: false, testID: 'millennium.library.cover.source.' + entry.id,
                        style: { width: entry.baseWidth, height: entry.baseWidth * 1.5 }
                    }, React.createElement(Grid.Cover, { item: entry.item, index: entry.index,
                        renderItem: original.renderItem, runtime: r })));
                }), React.createElement(Grid.Overlay, {
                    mapping: mapping, baseWidth: baseWidth, scrollY: scrollY.current
                })),
                React.createElement(Native.View, { key: 'empty', style: { paddingHorizontal: 12 } },
                    r.ready && !games.length ? element(original.ListEmptyComponent) : null),
                React.createElement(Native.View, { key: 'footer',
                    style: [original.ListFooterComponentStyle, { paddingHorizontal: 12 }],
                    onLayout: function(event) { r.footer = event.nativeEvent.layout.height; tryHandoff(); }
                }, element(original.ListFooterComponent))))));
        }
        // Injected source does not pass through Babel. Steam's Hermes rejects class declarations,
        // even inside an uncalled function, so keep this React boundary as a prototype subclass.
        function LibraryGridBoundary(props, context, updater) {
            React.Component.call(this, props, context, updater);
            this.state = { failed: false };
        }
        LibraryGridBoundary.prototype = Object.create(React.Component.prototype);
        LibraryGridBoundary.prototype.constructor = LibraryGridBoundary;
        LibraryGridBoundary.getDerivedStateFromError = function() { return { failed: true }; };
        LibraryGridBoundary.prototype.render = function() {
            if (this.state.failed) return React.createElement(Native.FlatList,
                Object.assign({}, this.props, { key: 'native_' + this.props.numColumns }));
            return React.createElement(LibraryGrid, this.props);
        };
        function intercept(name) {
            var original = base[name];
            return function(type, props, key) {
                if (type === Native.FlatList && typeof key === 'string' && key.indexOf('flatlist_') === 0 &&
                    props && Array.isArray(props.data) && typeof props.renderItem === 'function' &&
                    typeof props.getItemLayout === 'function' && props.numColumns >= 2) {
                    return original.call(base, LibraryGridBoundary, props, 'millennium-library-grid');
                }
                return original.apply(base, arguments);
            };
        }
        return facade(facade(base, 'jsx', intercept('jsx')), 'jsxs', intercept('jsxs'));
    }
""".trimIndent()
}
