/**
 * PM2 Ecosystem File for jt400-proxy-client (Standalone HTTP Server)
 *
 * Usage:
 *   pm2 start ecosystem.config.js --env production
 *   pm2 save
 *   pm2 startup
 *
 * Common commands:
 *   pm2 logs jt400-proxy-client
 *   pm2 restart jt400-proxy-client
 *   pm2 stop jt400-proxy-client
 */

module.exports = {
  apps: [
    {
      name: "jt400-proxy-client",
      script: "bin/server.js",

      // Number of instances (use 'max' for one per CPU core, or a number)
      instances: 1,
      exec_mode: "fork",   // 'cluster' is usually not needed for this since the client manages its own connection pool

      // Environment variables
      env: {
        NODE_ENV: "development",
        JT400_PROXY_HOST: "localhost",
        JT400_PROXY_PORT: 9400,
        JT400_FACADE_HTTP_PORT: 3456,
        LOG_LEVEL: "info"
      },

      env_production: {
        NODE_ENV: "production",
        JT400_PROXY_HOST: "your-jt400-proxy-host",
        JT400_PROXY_PORT: 9400,
        JT400_FACADE_HTTP_PORT: 3456,
        LOG_LEVEL: "info"
      },

      // Logging
      log_date_format: "YYYY-MM-DD HH:mm:ss Z",
      error_file: "./logs/jt400-proxy-error.log",
      out_file: "./logs/jt400-proxy-out.log",
      merge_logs: true,

      // Auto restart settings
      autorestart: true,
      watch: false,
      max_memory_restart: "300M",

      // Graceful shutdown
      kill_timeout: 10000,

      // Optional: source map support if you ever transpile
      source_map_support: false
    }
  ]
};

/**
 * Recommended: Install pm2-logrotate for automatic log rotation
 *
 *   pm2 install pm2-logrotate
 *
 * Then configure (example):
 *   pm2 set pm2-logrotate:max_size      20M
 *   pm2 set pm2-logrotate:retain        10
 *   pm2 set pm2-logrotate:compress      true
 *   pm2 set pm2-logrotate:dateFormat    YYYY-MM-DD_HH-mm-ss
 *
 * This prevents the log files from growing unbounded in production.
 */