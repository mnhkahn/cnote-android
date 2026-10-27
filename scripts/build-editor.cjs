const fs=require('node:fs'),path=require('node:path'),esbuild=require('esbuild');
const root=path.resolve(__dirname,'..'),target=path.join(root,'app/src/main/assets/editor/compare.js');
const output=esbuild.buildSync({entryPoints:[path.join(__dirname,'editor/compare.cjs')],bundle:true,platform:'browser',format:'iife',target:'es2020',minify:true,charset:'ascii',legalComments:'none',write:false}).outputFiles[0].contents;
if(process.argv.includes('--check')){if(!fs.existsSync(target)||!fs.readFileSync(target).equals(Buffer.from(output)))throw Error('compare.js is stale: run npm run build:editor');}else fs.writeFileSync(target,output);
const license=fs.readFileSync(path.join(root,'node_modules/diff/LICENSE'),'utf8');
const licensePath=path.join(root,'app/src/main/assets/editor/Diff-LICENSE.txt');
if(!process.argv.includes('--check'))fs.writeFileSync(licensePath,license);
