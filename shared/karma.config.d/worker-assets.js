// karma-webpack only registers entry scripts by default. Workers and WASM also
// need to be served, with URLs that work on Windows as well as Unix.
const path = require("node:path");
const assets = path.resolve(config.basePath, "worker-assets");
config.webpack.output = { path: assets, publicPath: "/base/worker-assets/" };
config.files.push({ pattern: path.join(assets, "**/*"), included: false, served: true, watched: false });
config.client.mocha = { ...config.client.mocha, timeout: 45000 };
