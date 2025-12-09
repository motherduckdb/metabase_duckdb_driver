{
  description = "Metabase DuckDB Driver";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    utils,
    ...
  }:
    utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config = {};
        };

        # Build script that automates the entire process
        buildDriver = pkgs.writeShellScriptBin "build-driver" ''
          set -euo pipefail

          DRIVER_DIR="$(pwd)"
          BUILD_DIR="''${BUILD_DIR:-$(mktemp -d)}"
          METABASE_VERSION="''${METABASE_VERSION:-v0.56.9}"

          echo "Building Metabase DuckDB driver..."
          echo "  Driver source: $DRIVER_DIR"
          echo "  Build directory: $BUILD_DIR"
          echo "  Metabase version: $METABASE_VERSION"
          echo ""

          # Clone metabase if not already present
          if [ ! -d "$BUILD_DIR/metabase" ]; then
            echo "Cloning Metabase $METABASE_VERSION..."
            ${pkgs.git}/bin/git clone --depth 1 --branch "$METABASE_VERSION" \
              https://github.com/metabase/metabase.git "$BUILD_DIR/metabase"
          else
            echo "Using existing Metabase clone at $BUILD_DIR/metabase"
          fi

          # Create driver directory in metabase
          mkdir -p "$BUILD_DIR/metabase/modules/drivers/duckdb"

          # Copy driver source
          echo "Copying driver source..."
          cp -rf "$DRIVER_DIR/src" "$BUILD_DIR/metabase/modules/drivers/duckdb/"
          cp -rf "$DRIVER_DIR/resources" "$BUILD_DIR/metabase/modules/drivers/duckdb/"
          cp -f "$DRIVER_DIR/deps.edn" "$BUILD_DIR/metabase/modules/drivers/duckdb/"

          # Add duckdb to metabase drivers deps.edn
          DRIVERS_DEPS="$BUILD_DIR/metabase/modules/drivers/deps.edn"
          if ! grep -q 'metabase/duckdb' "$DRIVERS_DEPS"; then
            echo "Adding duckdb driver to Metabase deps.edn..."
            ${pkgs.gnused}/bin/sed -i 's|metabase/vertica.*{:local/root "vertica"}}|metabase/vertica            {:local/root "vertica"}\n  metabase/duckdb              {:local/root "duckdb"}}|' "$DRIVERS_DEPS"
          fi

          # Build the driver
          echo "Building driver JAR..."
          cd "$BUILD_DIR/metabase"
          ${pkgs.clojure}/bin/clojure -X:build:drivers:build/driver :driver :duckdb

          # Copy output
          OUTPUT_JAR="$BUILD_DIR/metabase/resources/modules/duckdb.metabase-driver.jar"
          if [ -f "$OUTPUT_JAR" ]; then
            cp "$OUTPUT_JAR" "$DRIVER_DIR/"
            echo ""
            echo "SUCCESS: Built $DRIVER_DIR/duckdb.metabase-driver.jar"
          else
            echo "ERROR: JAR not found at expected location: $OUTPUT_JAR"
            exit 1
          fi
        '';
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Java runtime and development
            jdk21

            # Clojure tooling
            clojure
            leiningen

            # Build utilities
            git
            gnused
            coreutils

            # Helper script
            buildDriver
          ];

          shellHook = ''
            echo "Metabase DuckDB Driver Development Environment"
            echo ""
            echo "Commands:"
            echo "  build-driver    - Build the driver JAR (clones Metabase, builds, outputs JAR)"
            echo ""
            echo "Environment variables:"
            echo "  METABASE_VERSION - Metabase version to build against (default: v0.56.9)"
            echo "  BUILD_DIR        - Directory for build artifacts (default: temp dir)"
            echo ""
          '';
        };

        # Also expose the build script as a package
        packages.build-driver = buildDriver;
      }
    );
}
