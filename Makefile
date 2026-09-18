# Build and run this driver against a real Metabase, with nothing but Docker.
#
#   make dist                      = dist/duckdb.metabase-driver.jar from this working tree
#   make dist DRIVER_VERSION=1.5.4 = ... or a published release instead of a local build
#   make dev                       = Metabase + that jar on http://localhost:3000
#   make dev MB_PORT=3100          = ... on another port, to run two worktrees at once
#   make test                       = driver test suite against local DuckDB files
#   make test DRIVERS=motherduck   = ... against MotherDuck (needs motherduck_token)
#   make test TEST=metabase.driver.duckdb-test  = one namespace
#   make repl                      = Metabase REPL with the driver on the classpath
#   make logs / make stop          = tail / stop the dev Metabase
#   make clean                     = drop dist/, the dev container and its app db
#   make distclean                 = also drop the cached Metabase checkout and Maven repo
.PHONY: dev dist test repl logs stop clean distclean

# Metabase revision the driver is built and tested against.
MB_REF     ?= master
# metabase.jar release `make dev` runs.
MB_VERSION ?= 0.58.9
# Host port for `make dev`; change it to run two worktrees at once.
MB_PORT    ?= 3000
CLJ_IMAGE  ?= clojure:temurin-21-tools-deps
DRIVERS    ?= duckdb
TEST       ?=
DRIVER_VERSION ?=
export MB_VERSION MB_PORT

# Shared by all worktrees of this repo, so a second branch reuses the ~600MB
# Metabase checkout and the Maven cache instead of fetching them again.
CACHE  ?= $(HOME)/.cache/metabase-duckdb-driver
MB_DIR  = $(CACHE)/metabase

JAR = dist/duckdb.metabase-driver.jar

# This working tree *is* modules/drivers/duckdb, mounted in — the Metabase
# checkout stays clean apart from the deps patch it needs to see the driver.
# .claude is masked because git worktrees live under it: mounting them into the
# Metabase tree would hand the build a second copy of this driver's sources.
TTY = $(shell [ -t 0 ] && echo -it)
CLJ = docker run --rm $(TTY) -u $$(id -u):$$(id -g) -e HOME=/home/build \
	-e motherduck_token \
	-v "$(CACHE)/home:/home/build" -v "$(MB_DIR):/mb" -v "$(CURDIR):/mb/modules/drivers/duckdb" \
	-v /mb/modules/drivers/duckdb/.claude \
	-w /mb $(CLJ_IMAGE)

PATCH = python3 modules/drivers/duckdb/ci/patch-metabase.py

dev: $(JAR)
	docker compose up -d --build
	@printf "Waiting for Metabase to come up"; \
	until [ "$$(curl -fsS http://localhost:$(MB_PORT)/api/health 2>/dev/null)" = '{"status":"ok"}' ]; do \
	  printf '.'; sleep 3; \
	  docker compose ps --status running -q metabase | grep -q . || { echo " container died - make logs"; exit 1; }; \
	done; \
	echo " up: http://localhost:$(MB_PORT) (driver: $$(ls -la dist/*.jar | awk '{print $$5" bytes"}'))"

dist: $(JAR)

$(JAR): $(shell find src resources -type f) deps.edn
	mkdir -p dist
ifeq ($(DRIVER_VERSION),)
	$(MAKE) $(MB_DIR)
	$(CLJ) bash -c '$(PATCH) && ./bin/build-driver.sh duckdb'
	cp $(MB_DIR)/resources/modules/duckdb.metabase-driver.jar $@
else
	curl -fsSL -o $@ https://github.com/motherduckdb/metabase_duckdb_driver/releases/download/$(DRIVER_VERSION)/duckdb.metabase-driver.jar
endif

# The suite is Metabase's own driver test suite, so it runs from the checkout.
test: $(MB_DIR)
	$(CLJ) bash -c '$(PATCH) && DRIVERS=$(DRIVERS) clojure -X:dev:ci:ee:ee-dev:drivers:drivers-dev:test \
		$(if $(TEST),:only $(TEST),)'

repl: $(MB_DIR)
	$(CLJ) bash -c '$(PATCH) && clojure -M:dev:ee:ee-dev:drivers:drivers-dev:repl'

logs:
	docker compose logs -f metabase

stop:
	docker compose down

$(MB_DIR):
	git clone --filter=blob:none https://github.com/metabase/metabase.git $@
	git -C $@ checkout $(MB_REF)

clean:
	rm -rf dist
	docker compose down -v

distclean: clean
	rm -rf $(CACHE)
