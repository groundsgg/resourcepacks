import assert from 'node:assert/strict'; import test from 'node:test';
import { strictArgs } from '../src/cli.mjs';
import { readManifest } from '../src/manifest.mjs';
import { createReleaseFixture } from './fixtures.mjs';
test('strictArgs rejects unknown duplicate and missing arguments with stable diagnostics',()=>{assert.throws(()=>strictArgs(['--x','1'],['--manifest']),/invalid command arguments/);assert.throws(()=>strictArgs(['--manifest','a','--manifest','b'],['--manifest']),/invalid command arguments/);assert.throws(()=>strictArgs([],['--manifest']),/missing required/);});
test('readManifest accepts the exact canonical product manifest',async()=>{const fixture=await createReleaseFixture();assert.equal((await readManifest(`${fixture.root}/manifest.json`)).manifest.catalog.file,'grounds-resourcepacks-catalog-0.1.0.jar');});
