String call(String codeQLDatabaseDirectoryPath = 'codeql/databases', String pathToBuildScript = '', String querySuite = 'code-scanning') {
    def scriptcontents = libraryResource "scripts/New-CodeQLScan.ps1"
    writeFile file: "New-CodeQLScan.ps1", text: scriptcontents
    env.codeQLDatabaseDirectoryPath = codeQLDatabaseDirectoryPath
    env.pathToBuildScript = pathToBuildScript
    env.querySuite = querySuite
    pwsh(returnStdout: true, script: './New-CodeQLScan.ps1 -token $env:GITHUB_TOKEN -codeQLDatabaseDirectoryPath $env:codeQLDatabaseDirectoryPath -pathToBuildScript $env:pathToBuildScript -querySuite $env:querySuite')
}
