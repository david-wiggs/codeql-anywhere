# Overview 
During a presentation at the PowerShell Summit, it was mentioned that [with the onset of PowerShell Core](https://devblogs.microsoft.com/powershell/powershell-core-now-available-as-a-snap-package/) PowerShell aims to be _ubiquitous_. This really stuck with me, and is a primary reason why I think PowerShell is 👑. Regardless, there is no reason that CodeQL should also not be _ubiquitous_. Let's see how deep we can get in the pocket by wrapping the needed integration steps with PowerShell functions 💪. 

While there has historically always been the ability to leverage CodeQL within 3rd party CI/CD systems, the CodeQL runner [has been deprecated](https://github.blog/changelog/2021-09-21-codeql-runner-deprecation/). This repository aims to create, to the greatest extent possible, an environment agnostic (e.g., Azure DevOps, Jenkins, or even your local machine) means to to use CodeQL.

---

- [Requirements](#requirements)
- [Approach](#approach)
- [Assumptions](#assumptions)
- [Interpreted Languages](#interpreted-languages)
- [Compiled Languages](#compiled-languages)
  - [C / C++](#c--c)
  - [C#](#c)
  - [Java](#java)
  - [Go](#go)
  - [Build Script](#build-script)
- [Examples](#examples)
  - [Local Machine](#local-machine)
  - [Azure DevOps](#azure-devops)
  - [Jenkins](#jenkins)
- [Dot Sourcing](#dot-sourcing)

# Requirements
- PowerShell Core (i.e., [PowerShell 7](https://docs.microsoft.com/en-us/powershell/scripting/install/installing-powershell?view=powershell-7.2))

That's pretty much it... 

# Approach
1. Download the latest CodeQL bundle for the detected OS
2. Detect the languages of a given repository to determine which ones are supported by CodeQL
3. Create CodeQL databases, analyze, and upload results for each supported language

# Assumptions
- GitHub is used as a source control platform
  - The repository is either _public_, or GitHub Advanced Security is enabled if _private_
- The working directory is a `git` repository
- The Personal Access Token that is used has the ability to write `security_events` for a given repository
- We don't use the `codeql` CLI to upload SARIF results because it currently (❓) doesn't support the ability to record the length of time a given scan takes. This leaves an undesirable _Unknown_ in the Duration field of the banner that is above the alerts table in the Code scanning section of a given repository.  

# Interpreted Languages
CodeQL supports the following interpreted languages (i.e., languages that don't require compilation):
- Python
- JavaScript
- TypeScript
- Ruby

As such, if any of these languages is found to be present in a given repository, CodeQL will execute queries to analyze that language, respectively.

# Compiled Languages
The CodeQL currently supports [_autobuild_ features](https://codeql.github.com/docs/codeql-cli/creating-codeql-databases/#detecting-the-build-system). However, when complex combinations of compiled languages are passed in when using the `--db-cluster` parameters, sometimes the CodeQL cli will fail to build applications correctly, even if default application files, directories, an frameworks are leveraged. Because of this the `New-CodeQLScan` function attempts to build each compiled language, in serial, as described below. 

## C / C++
In an attempt to cover both C and C++ the `New-CodeQLScan` functions assumes a `MAKEFILE` exists in the source root. A simple `make` is issued to compile source code. 

## C#
Assuming that the appropriate version of .NET is installed, `New-CodeQLScan` will attempt to execute `dotnet build /p:UseSharedCompilation=false /t:rebuild` in the source root. Note that the `/p:UseSharedCompilation=false` flag is required per [CodeQL documentation](https://codeql.github.com/docs/codeql-cli/creating-codeql-databases/#specifying-build-commands). It is also recommended that the `/t:rebuild` flag be used to ensure that the entire build process is captured. 

## Java
Assuming that Java is installed and the `$JAVA_HOME` environment variable is set appropriately, the `New-CodeQLScan` function will attempt to determine the appropriate build method. A general assumption is that if Maven or Gradle is used, that a respective wrapper file(s) exist (e.g., mvnw, or gradlew) somewhere in the working repository.

### Maven
Assuming that Maven is installed, if a file that matches `*mvn*` is present in the repository, then an attempt to build the Java code will be made by executing `mvn clean --file $($pomFile.FullName) install` where `$($pomFile.FullName)` is the full path to `pom.xml` (assuming it exists).

### Gradle
Assuming that Gradle is installed, if a file that matches `*gradle*` is present in the repository, then an attempt to build the Java code will be made by executing `gradle --no-daemon clean test`.

### Ant
Assuming that Ant is installed, if a file that matches `*build.xml` is present in the repository, then an attempt to build the Java code will be made by executing  `ant -f $antBuildFile`.

## Go
The `New-CodeQLScan` function leverages the `CODEQL_EXTRACTOR_GO_BUILD_TRACING=on` environment variable in an attempt to automatically build Go source code. The first directory that contains a `.go` file is used to execute the build commands. If a specific build script is needed to compile the Go source code, consider leveraging the `-pathToBuildScript` parameter to specify the path to a file that gives general steps (e.g., scripts/build.sh). 

## Build Script
If the automated build logic for compiled languages does not fit your application, consider creating a file that defines the required build steps (e.g., commands). If there are multiple compiled languages in your application, this file should contain the needed steps to compile all languages, as only a single build file can be specified.

Specify a build script file. 
```powershell
New-CodeQLScan -token $env:GITHUB_TOKEN -pathToBuildScript 'scripts/build.sh'
```

# Examples
This section aims to demonstrate the portability of `codeql-anywhere`.

## Local Machine
The following examples assume that a GitHub Personal Access Token (PAT) is saved to the `$GITHUB_TOKEN` environment variable. 

Ensure that the functions in the `functions.ps1` file of this repository are loaded into memory. Do this by either copy / pasting them into a PowerShell session, or [dot source](#dot-sourcing) them. The following example specifies to retain the SARIF files in the source root for further exploration.

```powershell
New-CodeQLScan -token $env:GITHUB_TOKEN -keepSarif
```

Alternatively you could create the CodeQL databases in a temporary directory.
```powershell
$activeTempRoot = (Get-PSDrive | Where-Object {$_.name -like 'Temp'}).Root
$codeQLDatabaseDirectory = Join-Path -Path $activeTempRoot 'codeql/databases'
New-CodeQLScan -token $env:GITHUB_TOKEN -codeQLDatabaseDirectory $codeQLDatabaseDirectory
```

Specify an alternative query suite. 
```powershell
New-CodeQLScan -token $env:GITHUB_TOKEN -querySuite 'security-and-quality'
```

## Azure DevOps
Using the [PowerShell task](https://docs.microsoft.com/en-us/azure/devops/pipelines/tasks/utility/powershell?view=azure-devops) in Azure DevOps, we can call the `New-CodeQLScan.ps1` script. Note that the `GITHUB_TOKEN` environment variable must be set to pass in the secret value defined in the `$(GITHUB_TOKEN)` pipeline variable.

The duplicate `checkout` step is due to the fact that the default checkout leaves the working directory in state of a detached `HEAD`. Because the functions leverage the output of `git symbolic-ref HEAD`, the second checkout must be executed, as well as changes fetched.

```yaml
jobs:
- job: Job_1 
  displayName: CodeQL Scan
  pool:
    name: Default
  steps:
  - task: PowerShell@2
    displayName: 'Checkout codeql-anywhere'
    inputs:
      targetType: inline
      script: |
        if (Test-Path './.git/modules/codeql-anywhere') {rm -rf './.git/modules/codeql-anywhere'}
        git submodule add https://github.com/david-wiggs/codeql-anywhere.git
      pwsh: true
  - task: PowerShell@2
    displayName: 'Run New-CodeQLScan.ps1'
    inputs:
      targetType: filePath
      filePath: 'codeql-anywhere/resources/scripts/New-CodeQLScan.ps1'
      pwsh: true
    env:
      GITHUB_TOKEN: $(GITHUB_TOKEN)
```

## Jenkins
This repository has the folder structure such that it can be consumed as a Jenkins [Shared Library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/). Additionally, it contains the needed groovy script to call the `New-CodeQLScan.ps1` PowerShell script. Below is an example of a Jenkins Pipeline script that could be used. It assumes that there is a Jenkins credential named `codeql-token` of type _Secret Text_ that is the GitHub Personal Access Token to be used that uploads CodeQL results.

```groovy
@Library('codeql-anywhere') _
pipeline {
  agent any
  environment {
        GITHUB_TOKEN = credentials('codeql-token')
  }
  stages {
    stage('Checkout') {
      steps {
        git branch: 'main',
            url: 'https://github.com/david-wiggs/codeql-testing.git'
      }
    }
    stage('Execute CodeQL Scan') {
      steps {
        codeqlScan()
      }
    }
  }
}
```

## Dot Sourcing
Execute the below in a PowerShell session to dot source the functions in the `functions.ps1` file in the `main` branch.

```powershell
function Get-GitHubRepositoryFileContent {
    [CmdletBinding()]
    Param
    (
        [Parameter(Mandatory = $True)] [string] $gitHubRepository,
        [Parameter(Mandatory = $True)] [string] $path,
        [Parameter(Mandatory = $True)] [string] $branch,
        [Parameter(Mandatory = $False)] [string] $token
    )
    $uri = "https://api.github.com/repos/$gitHubRepository/contents/$path`?ref=$branch" # Need to escape the ? that indicates an http query
    $uri = [uri]::EscapeUriString($uri)
    $splat = @{
        Method = 'Get'
        Uri = $uri
        Headers = $headers
        ContentType = 'application/json'
    }
    if ($PSBoundParameters.ContainsKey('token')) {
        $headers = @{'Authorization' = "token $token"}
        $splat.Add('Headers', $headers)
    } 
    
    try {
        $fileData = Invoke-RestMethod @splat
        [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($fileData.content)) | Out-File -FilePath $(Split-Path $path -Leaf) -Force
        Get-Item -Path $(Split-Path $path -Leaf)
    } catch {
        Write-Warning "Unable to get file content."   
        $ErrorMessage = $_.Exception.Message
        Write-Warning "$ErrorMessage"
        break
    }
}

function Get-DotSourceFileFromGitHub {
    [CmdletBinding()]
    Param
    (
        [Parameter(Mandatory = $True)] [string] $gitHubRepository,
        [Parameter(Mandatory = $True)] [string] $path,
        [Parameter(Mandatory = $True)] [string] $branch,
        [Parameter(Mandatory = $False)] [string] $token
    )
    
    $splat = @{
        gitHubRepository = $gitHubRepository
        path = $path
        branch = $branch
    }
    if ($PSBoundParameters.ContainsKey('token')) {$splat.Add('token', $token)}
    $dotSourcefile = Get-GitHubRepositoryFileContent @splat 
    $content = Get-Content -Path $dotSourcefile.FullName 
    $content.Replace('function ', 'function Global:') | Out-File $dotSourceFile.FullName -Force
    . $dotSourcefile.FullName
    Remove-Item -Path $dotSourcefile.FullName -Force
}

$splat = @{
    gitHubRepository = 'david-wiggs/codeql-anywhere'
    path = 'resources/functions.ps1'
    branch = 'main'
}
Get-DotSourceFileFromGitHub @splat
```
