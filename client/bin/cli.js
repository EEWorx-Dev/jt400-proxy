#!/usr/bin/env node
'use strict';

/**
 * Standalone entry point for jt400-proxy-client (preferred Mode 2).
 *
 * Supports:
 * - Command line flags
 * - Environment variables
 * - Optional JSON config file (--config path/to/config.json)
 *
 * Run with:
 *   npx jt400-proxy-client
 *   jt400-proxy-client --port 4000 --proxy-host 10.0.0.5
 */

const fs = require('fs');
const path = require('path');
const { startServer } = require('../facade');

// --- Tiny CLI parser (no extra deps) ---
function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith('--')) {
      const [key, val] = arg.slice(2).split('=');
      if (val !== undefined) {
        args[key] = val;
      } else if (argv[i + 1] && !argv[i + 1].startsWith('--')) {
        args[key] = argv[++i];
      } else {
        args[key] = true;
      }
    }
  }
  return args;
}

function loadConfigFile(configPath) {
  if (!configPath) return {};
  try {
    const fullPath = path.resolve(configPath);
    const raw = fs.readFileSync(fullPath, 'utf8');
    const cfg = JSON.parse(raw);
    console.log(`[jt400-proxy-client] Loaded config from ${fullPath}`);
    return cfg;
  } catch (err) {
    console.error(`[jt400-proxy-client] Failed to load config file "${configPath}": ${err.message}`);
    process.exit(1);
  }
}

async function main() {
  const cli = parseArgs(process.argv);

  if (cli.help || cli.h) {
    console.log(`
jt400-proxy-client - Standalone HTTP server for jt400 (Mode 2)

Usage:
  jt400-proxy-client [options]

Options:
  --port <number>           HTTP port to listen on (default: 3456)
  --host <string>           HTTP host to bind to (default: 127.0.0.1)
  --proxy-host <string>     jt400-proxy TCP host
  --proxy-port <number>     jt400-proxy TCP port (default: 9400)
  --proxy-links <number>    Number of duplex connections (default: 3)
  --config <path>           Path to JSON config file
  --log-level <level>       error|warn|info|debug (default: info)
  --help                    Show this help

All options can also be set via environment variables (JT400_FACADE_HTTP_PORT, etc.).
    `);
    process.exit(0);
  }

  const fileConfig = loadConfigFile(cli.config || process.env.JT400_CONFIG);

  // Merge priority: CLI > Env > Config file > Defaults
  const options = {
    // Server
    port: cli.port || process.env.JT400_FACADE_HTTP_PORT || fileConfig.facade?.port,
    host: cli.host || process.env.JT400_FACADE_HOST || fileConfig.facade?.host,
    logLevel: cli['log-level'] || process.env.LOG_LEVEL || fileConfig.logLevel,

    // Proxy connection
    proxyHost: cli['proxy-host'] || process.env.JT400_PROXY_HOST || fileConfig.proxy?.host,
    proxyPort: cli['proxy-port'] || process.env.JT400_PROXY_PORT || fileConfig.proxy?.port,
    proxyLinks: cli['proxy-links'] || process.env.JT400_PROXY_NUM_LINKS || fileConfig.proxy?.numLinks,
    requestTimeoutMs: cli['request-timeout'] || process.env.JT400_PROXY_REQUEST_TIMEOUT_MS || fileConfig.proxy?.requestTimeoutMs,
  };

  try {
    const { server, client, close, logger } = await startServer(options);

    // Attach graceful shutdown to the returned server
    const shutdown = async (signal) => {
      logger.info(`${signal} received, shutting down...`);
      try {
        await close();
      } catch (e) {
        logger.error('Error during shutdown', { error: e.message });
      }
      process.exit(0);
    };

    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('SIGINT', () => shutdown('SIGINT'));

    // Keep process alive
  } catch (err) {
    console.error('Failed to start jt400-proxy-client:', err);
    process.exit(1);
  }
}

main();
