#!/usr/bin/env node
/**
 * Simple HTTP server for local testing of benchmark templates
 * Usage: node serve-local.js [port]
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = process.argv[2] || 8080;
const TEMPLATES_DIR = path.join(__dirname, '..', 'doc', 'templates');

// MIME types
const MIME_TYPES = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.css': 'text/css',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

// Create server
const server = http.createServer((req, res) => {
  let pathname = url.parse(req.url).pathname;
  
  // Default to index-visualizer.html
  if (pathname === '/') {
    pathname = '/index-visualizer.html';
  }
  
  const filePath = path.join(TEMPLATES_DIR, pathname);
  const ext = path.extname(filePath);
  
  // Check if file exists
  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('404 Not Found');
      return;
    }
    
    // Serve the file
    const mimeType = MIME_TYPES[ext] || 'application/octet-stream';
    res.writeHead(200, { 
      'Content-Type': mimeType,
      'Access-Control-Allow-Origin': '*' // Allow CORS for local testing
    });
    
    fs.createReadStream(filePath).pipe(res);
  });
});

// Start server
server.listen(PORT, () => {
  console.log('🚀 Starting local HTTP server for benchmark templates');
  console.log(`📁 Serving from: ${TEMPLATES_DIR}`);
  console.log(`🌐 URL: http://localhost:${PORT}`);
  console.log('');
  console.log('Available pages:');
  console.log(`  - http://localhost:${PORT}/index-visualizer.html    (Micro Benchmarks)`);
  console.log(`  - http://localhost:${PORT}/integration-index.html   (Integration Tests)`);
  console.log(`  - http://localhost:${PORT}/step-metrics-visualizer.html (Step Metrics)`);
  console.log(`  - http://localhost:${PORT}/performance-trends.html  (Performance Trends)`);
  console.log('');
  console.log('Press Ctrl+C to stop the server');
});