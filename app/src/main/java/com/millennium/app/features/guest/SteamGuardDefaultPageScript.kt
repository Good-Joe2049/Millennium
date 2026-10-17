package com.millennium.app.features.guest

/** Builds the Steam Guard navigator adapter used by the shared guest bootstrap. */
internal object SteamGuardDefaultPageScript {
    fun source(defaultRoute: String): String {
        val guardRoute = if (defaultRoute == "CodeFallback") "CodeFallback" else "GuardHome"
        return """
function guardNavigator(base, require) {
    if ('$guardRoute' !== 'CodeFallback') return base;
    return facade(base, 'createStackNavigator', function() {
        var stack = base.createStackNavigator.apply(base, arguments);
        var OriginalNavigator = stack.Navigator;
        var React = require(115);
        return facade(stack, 'Navigator', function(props) {
            if ('$guardRoute' === 'CodeFallback' && props &&
                props.initialRouteName === 'GuardHome') {
                props = Object.assign({}, props, { initialRouteName: 'CodeFallback' });
            }
            return React.createElement(OriginalNavigator, props);
        });
    });
}
""".trimIndent()
    }
}
