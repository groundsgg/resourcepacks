export const DEFAULT_NETWORK_TIMEOUT_MS=30_000;

export class OperationTimeoutError extends Error {}

export async function withTimeout(message,operation,timeoutMs=DEFAULT_NETWORK_TIMEOUT_MS){
  if(!Number.isSafeInteger(timeoutMs)||timeoutMs<=0||timeoutMs>DEFAULT_NETWORK_TIMEOUT_MS)throw new Error('invalid internal network timeout');
  const controller=new AbortController();const handlers=[];let timer;
  const timedOut=new Promise((resolve,reject)=>{timer=setTimeout(()=>{reject(new OperationTimeoutError(message));controller.abort();for(const handler of handlers){try{handler();}catch{}}},timeoutMs);});
  try{return await Promise.race([operation({signal:controller.signal,onTimeout:handler=>handlers.push(handler)}),timedOut]);}finally{clearTimeout(timer);}
}
