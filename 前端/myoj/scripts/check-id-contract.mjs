import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const openApi = JSON.parse(readFileSync('openapitools.json', 'utf8'))
const failures = []
const isId = (name) => name === 'id' || name.endsWith('Id')

for (const [modelName, model] of Object.entries(openApi.components.schemas)) {
  for (const [name, property] of Object.entries(model.properties ?? {})) {
    if (isId(name) && property.type === 'integer' && property.format === 'int64') {
      failures.push(`OpenAPI ${modelName}.${name} 仍是 int64 数字`)
    }
    if (name === 'ids' && property.items?.type === 'integer' && property.items?.format === 'int64') {
      failures.push(`OpenAPI ${modelName}.ids[] 仍是 int64 数字`)
    }
  }
}

for (const [path, methods] of Object.entries(openApi.paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    for (const parameter of operation.parameters ?? []) {
      if (isId(parameter.name) && parameter.schema?.type === 'integer' && parameter.schema?.format === 'int64') {
        failures.push(`OpenAPI ${method.toUpperCase()} ${path} 参数 ${parameter.name} 仍是 int64 数字`)
      }
    }
  }
}

for (const file of readdirSync('generated/models').filter((name) => name.endsWith('.ts'))) {
  const source = readFileSync(join('generated/models', file), 'utf8')
  for (const match of source.matchAll(/^\s*([A-Za-z][A-Za-z0-9]*)\??: (number|Array<number>);/gm)) {
    if (isId(match[1]) || match[1] === 'ids') {
      failures.push(`生成的模型 ${file} 中 ${match[1]} 仍是 ${match[2]}`)
    }
  }
}

for (const file of readdirSync('generated/services').filter((name) => name.endsWith('.ts'))) {
  const source = readFileSync(join('generated/services', file), 'utf8')
  for (const match of source.matchAll(/^\s*([A-Za-z][A-Za-z0-9]*)\??: number,/gm)) {
    if (isId(match[1])) {
      failures.push(`生成的服务 ${file} 中参数 ${match[1]} 仍是 number`)
    }
  }
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exitCode = 1
} else {
  console.log('长 ID 接口契约检查通过')
}
