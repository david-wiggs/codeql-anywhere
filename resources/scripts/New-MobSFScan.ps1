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
    branch = 'mobsfscan'
}
Get-DotSourceFileFromGitHub @splat

$splat = @{}
if ($null -ne $($env:GITHUB_TOKEN)) {$splat.Add('token', $env:GITHUB_TOKEN)} elseif ($PSBoundParameters.ContainsKey('token')) {$splat.Add('token', $token)}
New-MobSFScan @splat
