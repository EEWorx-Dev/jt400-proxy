# jt400-proxy-server Distribution

This is a ready-to-run distribution of the jt400-proxy-server (version ${project.version}).

## Contents

- `bin/` - Start and stop scripts (start.sh, stop.sh, start.bat, stop.bat, run.sh, run.bat)
- `lib/` - The executable shaded JAR (jt400-proxy-server-<version>.jar)
- `conf/` - Example configuration files (.env.example, env.bat.example, application.properties.example, logback.xml)
- `logs/` - Directory for runtime logs (empty)

## Quick Start

### 1. Configure

Copy the example and edit it:

```bash
cp conf/.env.example .env
# Edit .env with your AS400 credentials and settings
```

On Windows:

```cmd
copy conf\env.bat.example env.bat
```

### 2. Start the server

**Unix / Linux / macOS:**

```bash
bin/start.sh
```

**Windows:**

```cmd
bin\start.bat
```

The server will listen on the TCP port defined in your configuration (default 9400).

### 3. Stop the server

```bash
bin/stop.sh
# or on Windows: bin\stop.bat
```

**Note:** The scripts are located in `bin/` inside the distribution package. In the source tree they live under `server/scripts/`.

This README-dist.md is automatically renamed to README.md at the root of the distribution archive by the Maven Assembly descriptor.

## Configuration

All configuration is done via environment variables (see `.env.example` for the full list).

Key variables:

- `AS400_HOST`, `AS400_USER`, `AS400_PASSWORD`, `AS400_DATABASE`
- `PROXY_TCP_PORT` (default 9400)
- Hikari pool settings (HIKARI_*)
- Transaction timeout settings (TX_*)

See `conf/application.properties.example` for documentation on all supported variables.

## Running from source (developers)

If you are building from source:

```bash
mvn clean package
./run.sh
```

The `start.sh` / `start.bat` in a source checkout expect the JAR in `target/`.

## Systemd / Service (Linux)

Example systemd unit is not included but easy to create. Point it at `bin/start.sh` (or directly to the java command in lib/).

## Support

This is an internal tool for bridging legacy AS/400 access from Node.js applications.
