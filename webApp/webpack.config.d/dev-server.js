if (config.devServer) {
    config.devServer.port = 3000;
    config.devServer.host = "127.0.0.1";
    config.devServer.open = false;
    config.devServer.proxy = [{
        context: ["/api"],
        target: "http://127.0.0.1:8080",
        proxyTimeout: 660000,
        timeout: 660000,
        onProxyReq: function (proxyRequest, request, response) {
            // The body is already received when an import is cancelled. req.aborted
            // alone misses this case, so propagate an unfinished response close.
            response.on("close", function () {
                if (!response.writableEnded) proxyRequest.destroy();
            });
        }
    }];
}
