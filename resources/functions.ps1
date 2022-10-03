function Get-LatestCodeQLBundle {
    if ($PSVersionTable.OS -like '*windows*') {$os = 'windows'} elseif ($PSVersionTable.OS -like '*linux*') {$os = 'linux'} elseif ($PSVersionTable.OS -like 'darwin*') {$os = 'macos'} else {Write-Error "Could not determine OS."; break}

    $splat = @{
        Method = 'Get' 
        Uri = 'https://api.github.com/repos/github/codeql-action/releases/latest'
        ContentType = 'application/json'
    }
    $codeQlLatestVersion = Invoke-RestMethod @splat

    if ($os -like 'linux') {
        $bundleName = 'codeql-bundle-linux64.tar.gz'
    } elseif ($os -like 'macos') {
        $bundleName = 'codeql-bundle-osx64.tar.gz'
    } elseif ($os -like 'windows') {
        $bundleName = 'codeql-bundle-win64.tar.gz'
    }

    $splat = @{
        Method = 'Get' 
        Uri = "https://github.com/github/codeql-action/releases/download/$($codeQlLatestVersion.tag_name)/$bundleName"
        ContentType = 'application/zip'
    }
    $activeTempRoot = (Get-PSDrive | Where-Object {$_.name -like 'Temp'}).Root
    Remove-Item -Path "$activeTempRoot/codeql" -Recurse -Force
    Invoke-RestMethod @splat -OutFile "$activeTempRoot/$bundleName"
    $oldLocation = Get-Location
    Set-Location -Path $activeTempRoot
    tar -xzf $bundleName
    Set-Location -Path $oldLocation.Path
    "$activeTempRoot" + "codeql"
}

function Get-GitHubRepositoryLanguages {
    [CmdletBinding()]
    Param
    (
        [Parameter(Mandatory = $False)] [string] $token,
        [Parameter(Mandatory = $True)] [string] $owner,
        [Parameter(Mandatory = $True)] [string] $repositoryName
    )

    $uri = "https://api.github.com/repos/$owner/$repositoryName"
    $splat = @{
        Method = 'Get'
        Uri = $uri
        ContentType = 'application/json'
    }
    if ($PSBoundParameters.ContainsKey('token')) {
        $headers = @{'Authorization' = "token $token"}
        $splat.Add('Headers', $headers)
    } 
    $repository = Invoke-RestMethod @splat
    $splat.Uri = $repository.languages_url
    (Invoke-RestMethod @splat | Select-Object -First 1 | Get-Member -MemberType NoteProperty).Name
}

function Get-GitHubRepositorySupportedCodeQLLanguages {
    [CmdletBinding()]
    Param
    (
        [Parameter(Mandatory = $False)] [string] $token,
        [Parameter(Mandatory = $True)] [string] $owner,
        [Parameter(Mandatory = $True)] [string] $repositoryName
    )
    
    $splat = @{
        repositoryName = $repositoryName
        owner = $owner
    }
    if ($PSBoundParameters.ContainsKey('token')) {
        $splat.Add('token', $token)
    } 
    [array]$repositoryLanguages = Get-GitHubRepositoryLanguages @splat
    $supportedCodeQLLanguages = @('c++', 'c', 'c#', 'go', 'java', 'javascript', 'python', 'ruby', 'typescript')
    $repositorySupportedCodeQLLanguages = $repositoryLanguages | Where-Object {$_ -in $supportedCodeQLLanguages} 
    foreach ($language in $repositorySupportedCodeQLLanguages) {
        if ($language -like 'c++' -or $language -like 'c') {
            $prettyLanguage = 'cpp'
        } elseif ($language -like 'c#') {
            $prettyLanguage = 'csharp'
        } elseif ($language -like 'typescript') {
            $prettyLanguage = 'javascript'
        } else {
            $prettyLanguage = $language.ToLower()
        }
        [array]$returnLanguages += $prettyLanguage
    }
    $returnLanguages
}

function Get-GitHubRepositorySupportedMobSFScanLanguages {
    [CmdletBinding()]
    Param
    (
        [Parameter(Mandatory = $False)] [string] $token,
        [Parameter(Mandatory = $True)] [string] $owner,
        [Parameter(Mandatory = $True)] [string] $repositoryName
    )
    
    $splat = @{
        repositoryName = $repositoryName
        owner = $owner
    }
    if ($PSBoundParameters.ContainsKey('token')) {
        $splat.Add('token', $token)
    } 
    [array]$repositoryLanguages = Get-GitHubRepositoryLanguages @splat
    $supportedCodeQLLanguages = @('swift', 'kotlin', 'objective-c')
    $repositoryLanguages | Where-Object {$_ -in $supportedCodeQLLanguages} | ForEach-Object {$_.ToLower()}
}

function Set-GZipFile([ValidateScript({Test-Path $_})][string]$File){
 
    $srcFile = Get-Item -Path $File
    $newFileName = "$($srcFile.FullName).gz"
    try {
        $srcFileStream = New-Object System.IO.FileStream($srcFile.FullName,([IO.FileMode]::Open),([IO.FileAccess]::Read),([IO.FileShare]::Read))
        $dstFileStream = New-Object System.IO.FileStream($newFileName,([IO.FileMode]::Create),([IO.FileAccess]::Write),([IO.FileShare]::None))
        $gzip = New-Object System.IO.Compression.GZipStream($dstFileStream,[System.IO.Compression.CompressionMode]::Compress)
        $srcFileStream.CopyTo($gzip)
    } catch {
        Write-Host "$_.Exception.Message" -ForegroundColor Red
    } finally {
        $gzip.Dispose()
        $srcFileStream.Dispose()
        $dstFileStream.Dispose()
    }
}

function Set-GitHubRepositorySarifResults {
    [CmdletBinding()]
    Param (
        [Parameter(Mandatory = $True)] [string] $token ,
        [Parameter(Mandatory = $True)] [string] $owner,
        [Parameter(Mandatory = $True)] [string] $repository,
        [Parameter(Mandatory = $True)] [string] $ref,
        [Parameter(Mandatory = $True)] [string] $commitSha,
        [Parameter(Mandatory = $True)] [string] $startedAt,
        [Parameter(Mandatory = $True)] [string] $pathToSarif,
        [Parameter(Mandatory = $True)] [string] $checkoutUri,
        [Parameter(Mandatory = $True)] [string] $toolName
    )
    $headers = @{
        Authorization = "token $token"
        Accept = 'application/vnd.github+json'
    }

    # Creates a based64 encoded string of the gzip compressed .sarif file
    Set-GZipFile -File $pathToSarif
    $gZipSarifFile = Get-Item -Path "$pathToSarif.gz"
    $bytes = [System.IO.File]::ReadAllBytes($gZipSarifFile.FullName)
    $base64Sarif = [System.Convert]::ToBase64String($bytes, [System.Base64FormattingOptions]::None)
    $uri = "https://api.github.com/repos/$owner/$repository/code-scanning/sarifs"
    try {
        $splat = @{
            Method = 'Post'
            Uri = $uri
            Headers = $headers
            Body = @{
                commit_sha = $commitSha
                ref = $ref
                sarif = $base64Sarif
                checkout_uri = $checkoutUri
                tool_name = $toolName
                started_at = $startedAt
            } | ConvertTo-Json
            ContentType = 'application/json'
        }
        Invoke-RestMethod @splat
    } catch {
        Write-Warning "Unable to upload SARIF results."   
        $ErrorMessage = $_.Exception.Message
        Write-Warning "$ErrorMessage"
    }
}

function New-CodeQLScan {
    [CmdletBinding()]
    Param
    (
        [Parameter(Mandatory = $False)] [array] $languages,
        [Parameter(Mandatory = $False)] [string] $token,
        [Parameter(Mandatory = $False)] [string] $codeQLDatabaseDirectoryPath = 'codeql/databases',
        [Parameter(Mandatory = $False)] [string] $pathToBuildScript,
        [Parameter(Mandatory = $False)] [switch] $keepSarif,
        [Parameter(Mandatory = $False)] [switch] $preventUploadResultsToGitHubCodeScanning,
        [Parameter(Mandatory = $False)] [string] [ValidateSet('code-scanning', 'security-extended', 'security-and-quality')] $querySuite = 'code-scanning'
    )
    
    $originUrl = git remote get-url origin
    Write-Host "Origin URL is $originUrl."
    $owner = $originUrl.Split('/')[-2]
    Write-Host "Repository owner is $owner."
    $repositoryName = $originUrl.Split('/')[-1].Split('.')[0]
    Write-Host "Repository name is $repositoryName."
    $codeQLDatabaseDirectoryPath = 'codeql/databases'
    Write-Host "CodeQL database(s) directory is $codeQLDatabaseDirectoryPath."
    Write-Host "Query suite it $querySuite."
    if ($PSVersionTable.OS -like "Windows*") {$codeQlCmd = 'codeql.exe'} else {$codeQlCmd = 'codeql'}
    Write-Host "CodeQL executable is $codeqlCmd."
    if ($null -eq $env:CODEQL_LOCATION) {
        Write-Host "Getting latest CodeQL bundle."
        $codeQlDirectory = Get-LatestCodeQLBundle
        [System.Environment]::SetEnvironmentVariable('CODEQL_LOCATION',$codeQlDirectory,'Process')
    } else {
        $codeQlDirectory = $env:CODEQL_LOCATION
    }
    Write-Host "CodeQL directory is $codeqlDirectory."
    $sourceRoot = (Get-Location).Path
    
    $splat = @{
        owner = $owner
        repositoryName = $repositoryName
    }
    if ($PSBoundParameters.ContainsKey('token')) {$splat.Add('token', $token)}
    Write-Host "Detecting repository languages supported by CodeQL."
    [array]$repositoryCodeQLSupportedLaguages = Get-GitHubRepositorySupportedCodeQLLanguages @splat | Select-Object -Unique
    if ($null -ne $repositoryCodeQLSupportedLaguages) {
        Write-Host "The following languages that are supported by CodeQL were detected: $($repositoryCodeQLSupportedLaguages -join ', ')."
        if (Test-Path $codeQLDatabaseDirectoryPath) {Remove-Item -Path $codeQLDatabaseDirectoryPath -Recurse -Force} 
        $codeQLDatabaseDirectory = (New-Item -Path $codeQLDatabaseDirectoryPath -ItemType Directory).FullName
        New-Item -ItemType Directory -Path "$codeQLDatabaseDirectoryPath/inpterpretted" | Out-Null
        New-Item -ItemType Directory -Path "$codeQLDatabaseDirectoryPath/compiled" | Out-Null
    } else {
        Write-Warning "The repository, $owner/$repository does not contain any languages that are supported by CodeQL."
        break
    }
    $startedAt = (Get-date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    
    [array]$nonCompiledLanguages = $repositoryCodeQLSupportedLaguages | Where-Object {$_ -like 'javascript' -or $_ -like 'python' -or $_ -like 'ruby'}
    if ($null -ne $nonCompiledLanguages) {
        Write-Host "Creating CodeQL databases for non-compiled languages."
        if ($nonCompiledLanguages.Count -gt 1) {
            Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=$($nonCompiledLanguages -join ',') --source-root . --db-cluster $codeQLDatabaseDirectory/inpterpretted"
        } elseif ($nonCompiledLanguages.Count -eq 1) {
            Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=$($nonCompiledLanguages[0]) --source-root . $codeQLDatabaseDirectory/inpterpretted/$($nonCompiledLanguages[0])"
        }
    }
    
    [array]$compiledLanguages = $repositoryCodeQLSupportedLaguages | Where-Object {$_ -like 'cpp' -or $_ -like 'java' -or $_ -like 'csharp' -or $_ -like 'go'}
    if ($null -ne $compiledLanguages) {
        Write-Host "Creating CodeQL databases for compiled languages."        
        if (-not $PSBoundParameters.ContainsKey('pathToBuildScript')) {
            foreach ($language in $compiledLanguages) {
                if ($language -like 'cpp') {
                    try {
                        Write-Host "Attempting to build C / C++ project with Makefile."
                        Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=cpp --source-root . $codeQLDatabaseDirectory/compiled/cpp --command=make"
                    }
                    catch {
                        Write-Error "Unable able to autobuild C / C++ project and create a CodeQL database."
                        Write-Error "Consider supplying a path to a build script with the -pathToBuildScript parameter."
                    }
                } elseif ($language -like 'csharp') {
                    if ((Test-Path "*.csproj") -or (Test-Path "*.sln")) {
                        try {
                            Write-Host "Attempting to build C# project with dotnet build."
                            Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=csharp --source-root . $codeQLDatabaseDirectory/csharp --command='dotnet build /p:UseSharedCompilation=false /t:rebuild'"
                        }
                        catch {
                            Write-Error "Unable able to autobuild C# project and create a CodeQL database."
                            Write-Error "Consider supplying a path to a build script with the -pathToBuildScript parameter."
                        }
                    } else {
                        Write-Error "Unable able to autobuild C# project and create a CodeQL database."
                        Write-Error "Detected C# and did not find a .sln or .csproj file present in the root directory of the repository."
                    }
                    
                } elseif ($language -like 'java') {
                    $antBuildFile = Get-ChildItem -Path $sourceRoot -Recurse -Filter "*build.xml" | Select-Object -First 1
                    if ($null -ne $antBuildFile) {
                        $antBuildFile = $antBuildFile.FullName
                    }

                    if ($null -ne (Get-ChildItem -Path $sourceRoot -Recurse -Filter "*mvn*")) {
                        Write-Host "Detected Maven files."
                        $pomFile = Get-ChildItem -Recurse -Include 'pom.xml'
                        Write-Host "Attempting to build Java project with Maven."
                        if (Test-Path $pomFile.FullName) {
                            try {
                                Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=java --source-root . $codeQLDatabaseDirectory/java --command='mvn clean --file $($pomFile.FullName) install -DskipTests'"
                            }
                            catch {
                                Write-Error "Unable able to autobuild Java project."
                                Write-Error "Consider supplying a path to a build script with the -pathToBuildScript parameter."
                            }
                        } else {
                            Write-Error "Unable able to autobuild Java project and create a CodeQL database."
                            Write-Error "Detected Maven files but did not find a pom.xml file."
                        }
                    } elseif ($null -ne (Get-ChildItem -Path $sourceRoot -Recurse -Filter "*gradle*")) {
                        Write-Host "Detected Gradle files."
                        Write-Host "Attempting to build Java project with Gradle."
                        try {
                            # need to set working directory?
                            Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=java --source-root . $codeQLDatabaseDirectory/java --command='gradle --no-daemon clean test'"
                        }
                        catch {
                            Write-Error "Unable able to autobuild Java project and create a CodeQL database."
                            Write-Error "Consider supplying a path to a build script with the -pathToBuildScript parameter."
                        }
                    } elseif ($null -ne $antBuildFile) {
                        Write-Host "Detected Ant build files."
                        Write-Host "Attempting to build Java project with Ant."
                        try {
                            Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=java --source-root . $codeQLDatabaseDirectory/java --command='ant -f $antBuildFile'"
                        }
                        catch {
                            Write-Error "Unable able to autobuild Java project and create a CodeQL database."
                            Write-Error "Consider supplying a path to a build script with the -pathToBuildScript parameter."
                        }
                    }
                } elseif ($language -like 'go') {
                    Write-Host "Attempting to build Go project with CODEQL_EXTRACTOR_GO_BUILD_TRACING = 'on'."
                    try {
                        $goSourceRoot = Split-Path (Get-ChildItem -Recurse -Include '*.go' | Select-Object -First 1) -Parent
                        $env:CODEQL_EXTRACTOR_GO_BUILD_TRACING = 'on'
                        Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=go --source-root $sourceRoot $codeQLDatabaseDirectory/go --working-dir $goSourceRoot"
                    }
                    catch {
                        Write-Error "Unable able to autobuild Go project and create a CodeQL database."
                        Write-Error "Consider supplying a path to a build script with the -pathToBuildScript parameter."
                    }
                } 
            }
        } else {
            Write-Host "Using build script at $pathToBuildScript to build all detected compiled lanuages."
            try {
                Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database create --language=$($compiledLanguages -join ',') --source-root . $codeQLDatabaseDirectory/compiled --command='./$pathToBuildScript'"
            }
            catch {
                Write-Error "Unable able to build compiled language project(s) and create a CodeQL database(s)."
            }
        } 
    }
    
    Write-Host "Analyzing CodeQL databases."
    [array]$codeQLDatabases = Get-ChildItem -Path "$codeQLDatabaseDirectory/inpterpretted" -Directory -Exclude log, working
    [array]$codeQLDatabases += Get-ChildItem -Path "$codeQLDatabaseDirectory/compiled" -Directory -Exclude log, working
    foreach ($database in $codeQLDatabases) {
        $language = $database.Name
        $queries = Get-ChildItem -Recurse -Filter "*$language-$querySuite.qls"
        Write-Host "Analyzing $language database."
        Invoke-Expression -Command "$(Join-Path -Path $codeQlDirectory $codeQlCmd) database analyze $($database.FullName) $($queries.FullName) --format=sarifv2.1.0 --output=$language-results.sarif --sarif-category=$language"
        if (-not $preventUploadResultsToGitHubCodeScanning) {
            if ($null -ne $env:Build.SourceBranch) {$ref = $env:Build.SourceBranch} else {$ref = $(git rev-parse --verify HEAD)}
            $splat = @{
                owner = $owner
                repository = $repositoryName
                ref = $(git symbolic-ref HEAD)
                startedAt = $startedAt
                commitSha = $ref
                pathToSarif = "$language-results.sarif"
                checkoutUri = $sourceRoot
                toolName = 'CodeQL'
            }
            if ($PSBoundParameters.ContainsKey('token')) {$splat.Add('token', $token)}
            Write-Host "Uploading SARIF results for $owner / $repositoryName for $language to GitHub Code Scanning."
            Set-GitHubRepositorySarifResults @splat
        }
        Get-ChildItem -Path $sourceRoot -Filter "*-results.sarif.gz*" | Remove-Item -Force
        if (-not $keepSarif) {Get-ChildItem -Path $sourceRoot -Filter "*-results.sarif" | Remove-Item -Force}
    }
    Remove-Item -Path (Split-Path $codeQLDatabaseDirectory -Parent) -Recurse -Force
}
