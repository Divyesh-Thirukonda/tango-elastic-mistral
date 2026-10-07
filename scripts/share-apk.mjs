#!/usr/bin/env node
// Serves only the APK for copying to a phone; no pairing tokens or directory access.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { networkInterfaces } from 'node:os';
const apk = await readFile(fileURLToPath(new URL('../dist/tango-debug.apk', import.meta.url)));
const host=process.argv.includes('--lan')?'0.0.0.0':'127.0.0.1';
const port=8790;
const server=http.createServer((request,response) => {
  if(request.method!=='GET' || request.url!=='/tango.apk') { response.writeHead(404); response.end('Not found'); return; }
  response.writeHead(200, {'Content-Type':'application/vnd.android.package-archive','Content-Disposition':'attachment; filename="tango.apk"','Content-Length':apk.length,'Cache-Control':'no-store'});
  response.end(apk);
});
server.listen(port,host,() => {
  console.log('Open this address on your phone while it is on the same Wi-Fi:');
  if(host==='0.0.0.0') {
    for(const interfaces of Object.values(networkInterfaces()))
      for(const entry of interfaces??[]) if(entry.family==='IPv4'&&!entry.internal)console.log(`http://${entry.address}:${port}/tango.apk`);
  } else console.log(`http://127.0.0.1:${port}/tango.apk`);
  console.log('This server shares only the APK. Press Ctrl+C after downloading.');
});
server.on('error',error => { console.error(error.message); process.exitCode=1; });
process.once('SIGINT',() => server.close());
process.once('SIGTERM',() => server.close());
