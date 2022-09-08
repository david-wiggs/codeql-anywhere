String call() {
    def scriptcontents = libraryResource "scripts/New-MobSFScan.ps1"
    def mobsfconfig = libraryResource ".mobsf"
    writeFile file: "New-MobSFScan.ps1", text: scriptcontents
    writeFile file: ".mobsf", text: mobsfconfig
    pwsh(returnStdout: true, script: './New-MobSFScan.ps1 -token $env:GITHUB_TOKEN ')
}
