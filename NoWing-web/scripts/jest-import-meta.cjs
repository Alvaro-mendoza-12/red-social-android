exports.name = 'novvin-import-meta-test-env'
exports.version = 1
exports.factory = ({ configSet }) => context => {
  const ts = configSet.compilerModule
  const visit = node => {
    if (ts.isMetaProperty(node) && node.keywordToken === ts.SyntaxKind.ImportKeyword && node.name.text === 'meta') {
      return ts.factory.createObjectLiteralExpression([
        ts.factory.createPropertyAssignment('env', ts.factory.createObjectLiteralExpression([
          ts.factory.createPropertyAssignment('DEV', ts.factory.createTrue()),
          ts.factory.createPropertyAssignment('MODE', ts.factory.createStringLiteral('test')),
        ])),
      ])
    }
    return ts.visitEachChild(node, visit, context)
  }
  return source => ts.visitNode(source, visit)
}
