package com.millennium.app.features.library

/** Pinch lifecycle, Nagram density thresholds and native Animated event binding. */
internal object SteamLibraryGridGestureScript {
    fun source(): String = """
    function libraryGridGestureMachine(callbacks) {
        var active = false, accepted = false, baseline = 1, latest = 1, transition = null;
        function progress() {
            if (!transition) return 0;
            return Math.max(0, Math.min(1, (latest - transition.start) / (transition.end - transition.start)));
        }
        function reset() { active = false; accepted = false; transition = null; baseline = latest = 1; }
        function update(event) {
            if (!active || !accepted) return;
            if (event.numberOfPointers !== 2) { finish(true); return; }
            if (!Number.isFinite(event.scale) || event.scale <= 0) { finish(true); return; }
            latest = event.scale;
            if (!transition) {
                var ratio = latest / baseline;
                if (ratio <= 1.01 && ratio >= 0.99) return;
                var direction = ratio > 1 ? -1 : 1;
                var end = baseline * (direction < 0 ? 2 : 0.5);
                if (!callbacks.stage(direction, baseline, end)) { baseline = latest; return; }
                transition = { start: baseline, end: end };
            }
            var value = progress();
            if (value === 1 || value === 0) {
                callbacks.completeStage(value === 1);
                transition = null;
                baseline = latest;
            }
        }
        function start(event) {
            if (active) return;
            reset();
            if (event.numberOfPointers !== 2) return;
            active = true;
            accepted = callbacks.begin(event);
            if (accepted) update(event);
        }
        function finish(cancelled, event) {
            if (!active) return;
            if (event && Number.isFinite(event.scale) && event.scale > 0) latest = event.scale;
            var wasAccepted = accepted, hadTransition = transition !== null, value = progress();
            reset();
            if (!wasAccepted) return;
            if (hadTransition) callbacks.settle(!cancelled && value > 0.2, value);
            else callbacks.finish();
        }
        return { start: start, update: update, finish: finish, reset: reset };
    }
    function libraryGridGesture(Native, Handlers, callbacks) {
        var scale = new Native.Animated.Value(1);
        var machine = libraryGridGestureMachine(callbacks);
        return {
            scale: scale,
            event: Native.Animated.event([{ nativeEvent: { scale: scale } }], {
                useNativeDriver: true,
                listener: function(event) { machine.update(event.nativeEvent); }
            }),
            state: function(event) {
                var value = event.nativeEvent, State = Handlers.State;
                if (value.state === State.ACTIVE) machine.start(value);
                else if (value.state === State.END) machine.finish(false, value);
                else if (value.state === State.CANCELLED || value.state === State.FAILED) machine.finish(true, value);
            },
            reset: machine.reset
        };
    }
""".trimIndent()
}
