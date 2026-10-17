package com.millennium.app.features.library

/** Portrait geometry and native-driven transitions for Steam's library grid. */
internal object SteamLibraryGridTransitionScript {
    fun source(): String = """
    function libraryGridTransitions(React, Native) {
        var Animated = Native.Animated;
        var stops = [2, 3, 4, 5, 6, 9];
        function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }
        function adjacent(columns, direction) {
            return stops[clamp(stops.indexOf(columns) + direction, 0, stops.length - 1)];
        }
        function metrics(width, columns) {
            // Retain Steam's three-column spacing; halve gutters at nine columns.
            var gap = columns === 9 ? 5 : 10;
            var rowGap = columns === 9 ? 5.5 : 11;
            var cell = Math.max(1, (width - 24 - gap * (columns - 1)) / columns);
            return { columns: columns, width: width, cell: cell,
                height: cell * 1.5, gap: gap, rowGap: rowGap, stride: cell * 1.5 + rowGap };
        }
        function contentHeight(layout, count, header) {
            return header + (count ? Math.ceil(count / layout.columns) * layout.stride - layout.rowGap : 0);
        }
        function position(layout, row, column, header, offset) {
            return { x: 12 + column * (layout.cell + layout.gap),
                y: header + row * layout.stride - offset,
                width: layout.cell, height: layout.height };
        }
        function box(layout, index, header, offset) {
            return position(layout, Math.floor(index / layout.columns), index % layout.columns, header, offset);
        }
        function anchor(layout, count, header, offset, x, y) {
            if (!count || y < Math.max(0, header - offset)) return null;
            var row = Math.max(0, Math.floor((offset + y - header) / layout.stride));
            var col = clamp(Math.floor((x - 12) / (layout.cell + layout.gap)), 0, layout.columns - 1);
            var index = clamp(row * layout.columns + col, 0, count - 1);
            return { index: index, top: box(layout, index, header, offset).y };
        }
        function anchoredOffset(layout, count, header, height, selected) {
            var offset = header + Math.floor(selected.index / layout.columns) * layout.stride - selected.top;
            return clamp(offset, 0, Math.max(0, contentHeight(layout, count, header) - height));
        }
        function visible(layout, count, header, offset, height) {
            var first = Math.max(0, Math.floor((offset - header) / layout.stride) - 1);
            var last = Math.min(Math.ceil(count / layout.columns),
                Math.ceil((offset + height - header) / layout.stride) + 1);
            var result = [];
            for (var i = first * layout.columns; i < Math.min(count, last * layout.columns); i++) result.push(i);
            return result;
        }
        function interpolate(progress, from, to) {
            return from === to ? from : progress.interpolate({ inputRange: [0, 1], outputRange: [from, to], extrapolate: 'clamp' });
        }
        function stage(session, columns, progress) {
            var from = metrics(session.width, session.columns);
            var to = metrics(session.width, columns);
            var offset = anchoredOffset(to, session.data.length, session.header, session.height, session.anchor);
            return { from: from, to: to, fromOffset: session.offset, toOffset: offset,
                header: session.header, height: session.height, anchor: session.anchor.index,
                data: session.data, progress: progress, renderItem: session.renderItem };
        }
        function still(session) {
            var result = stage(session, session.columns, new Animated.Value(0));
            result.toOffset = result.fromOffset;
            return result;
        }
        function windowIndices(layout, count, header, offset, height, padding) {
            return visible(layout, count, header, Math.max(0, offset - padding), height + padding * 2);
        }
        function viewport(layout, count, header, offset, height) {
            return visible(layout, count, header, offset, height).filter(function(index) {
                var rect = box(layout, index, header, offset);
                return rect.y <= height && rect.y + rect.height >= 0;
            });
        }
        // Nagram pairs grid slots around the pinch anchor, not identical games.
        // The old image remains underneath while the target slot's image fades over it.
        function plan(transition) {
            if (!transition || transition.from.columns === transition.to.columns) return null;
            var from = transition.from, to = transition.to, count = transition.data.length;
            var source = viewport(from, count, transition.header, transition.fromOffset, transition.height);
            var target = viewport(to, count, transition.header, transition.toOffset, transition.height);
            if (!source.length || !target.length) return null;
            var first = source[0], firstTarget = target[0];
            var last = source[source.length - 1], lastTarget = target[target.length - 1];
            // SharedMediaLayout.dispatchDraw: pair rows relative to the two visible grids,
            // including its top/bottom corrections. Pool overscan is not a visible row.
            var rowsOffset = (Math.floor(transition.anchor / to.columns) - Math.floor(firstTarget / to.columns)) -
                (Math.floor(transition.anchor / from.columns) - Math.floor(first / from.columns));
            if ((Math.floor(first / from.columns) - rowsOffset < 0 && to.columns < from.columns) ||
                (Math.floor(firstTarget / to.columns) + rowsOffset < 0 && to.columns > from.columns)) rowsOffset = 0;
            if ((Math.floor(lastTarget / from.columns) + rowsOffset >= Math.ceil(count / from.columns) && to.columns > from.columns) ||
                (Math.floor(last / to.columns) - rowsOffset >= Math.ceil(count / to.columns) && to.columns < from.columns)) rowsOffset = 0;
            var rowShift = Math.floor(firstTarget / to.columns) - Math.floor(first / from.columns) + rowsOffset;
            var columnShift = Math.trunc((to.columns - from.columns) *
                (transition.anchor % from.columns) / (from.columns - 1));
            var used = new Set(), sources = new Map(), incoming = [];
            source.forEach(function(index) {
                var row = Math.floor(index / from.columns) + rowShift;
                var col = index % from.columns + columnShift;
                var next = row * to.columns + col;
                var valid = col >= 0 && col < to.columns && next >= firstTarget && next <= lastTarget;
                var cell = { source: index, target: valid ? next : null,
                    fadeOut: col < to.columns,
                    from: box(from, index, 0, transition.fromOffset),
                    to: position(to, row, col, 0, transition.toOffset) };
                sources.set(index, cell);
                if (valid) {
                    used.add(next);
                    if (next !== index) incoming.push(cell);
                }
            });
            target.forEach(function(index) {
                if (used.has(index)) return;
                var row = Math.floor(index / to.columns) - rowShift;
                var col = index % to.columns - columnShift;
                incoming.push({ source: null, target: index,
                    fadeIn: col < from.columns,
                    from: position(from, row, col, 0, transition.fromOffset),
                    to: box(to, index, 0, transition.toOffset) });
            });
            return { sources: sources, incoming: incoming, transition: transition };
        }
        function slotTransform(cell, baseWidth, progress, scrollY) {
            var from = cell.from, to = cell.to;
            return [
                { translateX: interpolate(progress, from.x + (from.width - baseWidth) / 2,
                    to.x + (to.width - baseWidth) / 2) },
                { translateY: Animated.add(scrollY, interpolate(progress, from.y + (from.height - baseWidth * 1.5) / 2,
                    to.y + (to.height - baseWidth * 1.5) / 2)) },
                { scale: interpolate(progress, from.width / baseWidth, to.width / baseWidth) }
            ];
        }
        function transform(layout, index, baseWidth, transition, scrollY, mapping) {
            if (mapping && mapping.sources.has(index)) {
                return slotTransform(mapping.sources.get(index), baseWidth, transition.progress, scrollY);
            }
            var rect = box(transition ? transition.from : layout, index, 0,
                transition ? transition.fromOffset : 0);
            var y = rect.y + (rect.height - baseWidth * 1.5) / 2;
            return [{ translateX: rect.x + (rect.width - baseWidth) / 2 },
                { translateY: transition ? Animated.add(scrollY, y) : y }, { scale: rect.width / baseWidth }];
        }
        function opacity(index, visible, mapping) {
            if (!visible) return 0;
            if (!mapping) return 1;
            var cell = mapping.sources.get(index);
            return !cell ? 0 : cell.target === null && cell.fadeOut ? interpolate(mapping.transition.progress, 1, 0) : 1;
        }
        function retain(pool, games, indices, required, pinned, baseWidth, limit) {
            var wanted = new Set(required);
            if (pinned) pinned.forEach(function(id) {
                var index = indices.get(id);
                if (index !== undefined) wanted.add(index);
            });
            pool.forEach(function(entry, id) { if (!indices.has(id)) pool.delete(id); });
            wanted.forEach(function(index) {
                if (index < 0 || index >= games.length) return;
                var item = games[index], id = String(item.appid);
                var entry = pool.get(id) || { id: id };
                entry.item = item; entry.index = index;
                // A rotation can change this size; switching density cannot.
                entry.baseWidth = baseWidth;
                pool.delete(id);
                pool.set(id, entry);
            });
            // Insertion order is an LRU. Required/pinned images survive a whole gesture.
            pool.forEach(function(entry, id) {
                if (pool.size > Math.max(limit, wanted.size) && !wanted.has(indices.get(id))) pool.delete(id);
            });
            var result = [];
            pool.forEach(function(entry, id) {
                entry.index = indices.get(id);
                entry.item = games[entry.index];
                entry.visible = wanted.has(entry.index);
                result.push(entry);
            });
            return result.sort(function(left, right) { return left.index - right.index; });
        }
        var Cover = React.memo(function Cover(props) {
            // This memo is deliberately below the animated wrapper. Stage/scroll changes do
            // not call Steam's portrait renderer or replace its image source/style objects.
            return React.useMemo(function() {
                var element = props.renderItem({ item: props.item, index: props.index, separators: {
                    highlight: function() {}, unhighlight: function() {}, updateProps: function() {}
                } });
                if (!React.isValidElement(element) || element.props.item !== props.item ||
                    typeof element.props.nColumns !== 'number') throw new Error('Unsupported Steam library portrait');
                var select = element.props.onSelected;
                return React.cloneElement(element, {
                    nColumns: 1, index: 0,
                    style: [element.props.style, { flex: 0, width: '100%', height: '100%',
                        marginLeft: 0, maxWidth: '100%' }],
                    onSelected: function(game) {
                        if (!props.runtime.session && props.runtime.focused && select) select(game);
                    }
                });
            }, [props.item, props.index, props.renderItem, props.runtime]);
        });
        function Overlay(props) {
            var mapping = props.mapping;
            if (!mapping) return null;
            var transition = mapping.transition;
            return React.createElement(Native.View, {
                pointerEvents: 'none', accessibilityElementsHidden: true,
                importantForAccessibility: 'no-hide-descendants',
                style: Native.StyleSheet.absoluteFillObject
            }, mapping.incoming.map(function(cell) {
                var item = transition.data[cell.target];
                return React.createElement(Animated.View, {
                    key: String(item.appid), pointerEvents: 'none',
                    style: { position: 'absolute', left: 0, top: 0,
                        width: props.baseWidth, height: props.baseWidth * 1.5,
                        opacity: cell.source !== null || cell.fadeIn ? transition.progress : 1,
                        transform: slotTransform(cell, props.baseWidth, transition.progress, props.scrollY) }
                }, React.createElement(Native.View, {
                    // Like Nagram's crossfadeView.draw(canvas): draw the target's existing
                    // native portrait. No second Cover, Expo.Image, URI or error callback.
                    collapsable: false, testID: 'millennium.library.cover.draw.' + item.appid,
                    pointerEvents: 'none', style: { width: props.baseWidth, height: props.baseWidth * 1.5 }
                }));
            }));
        }
        return { stops: stops, clamp: clamp, adjacent: adjacent, metrics: metrics,
            contentHeight: contentHeight, box: box, anchor: anchor, anchoredOffset: anchoredOffset,
            visible: visible, viewport: viewport, windowIndices: windowIndices, stage: stage, still: still,
            interpolate: interpolate, transform: transform, opacity: opacity, plan: plan,
            retain: retain, Cover: Cover, Overlay: Overlay };
    }
""".trimIndent()
}
