String call() {
    def scriptcontents = libraryResource "scripts/New-MobSFScan.ps1"
    writeFile file: "New-MobSFScan.ps1", text: scriptcontents
    pwsh(returnStdout: true, script: './New-MobSFScan.ps1 -token $env:GITHUB_TOKEN ')
}
