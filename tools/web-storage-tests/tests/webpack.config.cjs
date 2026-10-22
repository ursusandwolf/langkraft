const path = require("node:path");
module.exports = {
  mode: "development",
  target: "web",
  devtool: false,
  entry: path.resolve(__dirname, "harness.js"),
  output: { path: path.resolve(__dirname, "../build/tests"), filename: "harness.js", clean: true },
  resolve: {
    modules: [path.resolve(__dirname, "../node_modules"), "node_modules"],
    fallback: { fs: false, path: false }
  },
  module: { rules: [{ test: /\.wasm$/, type: "asset/resource" }] },
  performance: { hints: false }
};
