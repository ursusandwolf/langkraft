const CopyWebpackPlugin = require("copy-webpack-plugin");
const path = require("path");
const sqljsDirectory = path.dirname(require.resolve("sql.js/dist/sql-wasm.js"));
config.resolve.fallback = { ...(config.resolve.fallback || {}), fs: false, path: false, crypto: false };
config.plugins.push(new CopyWebpackPlugin({ patterns: [
    { from: path.join(sqljsDirectory, "sql-wasm.js"), to: "sql-wasm.js" },
    { from: path.join(sqljsDirectory, "sql-wasm.wasm"), to: "sql-wasm.wasm" }
] }));