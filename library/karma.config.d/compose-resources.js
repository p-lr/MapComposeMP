// Serve Compose Resources to the browser during `wasmJsBrowserTest`.
//
// `Res.readBytes` fetches "/composeResources/<qualifier>/<path>". The Kotlin Gradle plugin stages
// those files inside the karma package (`<basePath>/kotlin/composeResources`) but never adds them
// to karma's `files`, so every read answered 404 and every test reading a fixture failed. Karma
// serves an absolute path under "/absolute<path>", which is what the proxy maps onto.
config.files = config.files || [];
config.proxies = config.proxies || {};

const composeResources = config.basePath + "/kotlin/composeResources";

config.files.push({
    pattern: composeResources + "/**",
    included: false,
    served: true,
    watched: false,
    nocache: true,
});
config.proxies["/composeResources/"] = "/absolute" + composeResources + "/";
