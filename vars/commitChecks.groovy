String call(Map params) {
    def scriptcontents = libraryResource "scripts/Set-CommitStatusChecks.ps1"
    writeFile file: "Set-CommitStatusChecks.ps1", text: scriptcontents
    pwsh(returnStdout: true, script: "./Set-CommitStatusChecks.ps1 -token \$env:GITHUB_TOKEN")
}
