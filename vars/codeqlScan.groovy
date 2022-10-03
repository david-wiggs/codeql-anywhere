String call(Map params) {
    def scriptcontents = libraryResource "scripts/New-CodeQLScan.ps1"
    writeFile file: "New-CodeQLScan.ps1", text: scriptcontents
    if (params['codeQLDatabaseDirectoryPath']) {
        codeQLDatabaseDirectoryPath = params['codeQLDatabaseDirectoryPath']
    } else {
        codeQLDatabaseDirectoryPath = 'codeql/databases'
    }
    
    if (params['pathToBuildScript']) {
        pathToBuildScript = params['pathToBuildScript']
    } else {
        pathToBuildScript = ''
    }
    
    if (params['querySuite']) {
        querySuite = params['querySuite']
    } else {
        querySuite = 'code-scanning'
    }
    pwsh(returnStdout: true, script: "./New-CodeQLScan.ps1 -token \$env:GITHUB_TOKEN -codeQLDatabaseDirectoryPath ${codeQLDatabaseDirectoryPath} -pathToBuildScript ${pathToBuildScript} -querySuite ${querySuite}")
}
