import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Editor } from '../.test-build/editor.js';
test('late response keeps newer edits dirty', async () => {
 const e=new Editor();e.load({text:'原稿',revision:1});e.edit('第一稿');let done;
 const p=e.save(body=>new Promise(resolve=>{done=()=>resolve({...body,revision:2});}));
 e.edit('新编辑');done();await p;assert.equal(e.state,'DIRTY');assert.equal(e.text,'新编辑');
});
test('lost response retries original key before newer edit',async()=>{
 const e=new Editor();e.load({text:'原稿',revision:1});e.edit('一');let key;
 await assert.rejects(e.save(async(_,k)=>{key=k;throw Error('network');}));e.edit('二');
 await e.save(async(b,k)=>{assert.equal(k,key);assert.equal(b.text,'一');return {...b,revision:2};});assert.equal(e.state,'DIRTY');
 await e.save(async b=>({...b,revision:3}));assert.equal(e.state,'CLEAN');
});
test('conflict retains local draft and blocks overwrite',async()=>{
 const e=new Editor();e.load({text:'原稿',revision:1});e.edit('本地');
 await assert.rejects(e.save(async()=>{throw Object.assign(Error('冲突'),{status:409});}));
 assert.equal(e.state,'CONFLICT');assert.equal(e.text,'本地');assert.equal(await e.save(async()=>{throw Error('must not send');}),false);
});