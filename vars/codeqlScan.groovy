def call (Map params, Closure closure = null) {
    def languages = processLanguages(params['languages'])
    def ref = processRef(params['ref'])
    def ram = defaultIfNullOrEmtpy(params['ram'] as Integer, 4000)
    def threads = defaultIfNullOrEmtpy(params['threads'] as Integer, 1)
    def verbosity = defaultIfNullOrEmtpy(params['verbosity'], 'errors')
    def querySuite = defaultIfNullOrEmtpy(params['querySuite'], 'code-scanning')
    def origin = pwsh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    def org = origin.tokenize('/')[-2]
    def repo = origin.tokenize('/')[-1].tokenize('.')[0]
    def commit = pwsh(script: 'git rev-parse --verify HEAD', returnStdout: true).trim()

    createCodeqlFolders()
    codeqlInstall()

    def tmp = getCodeqlTempFolder()
    def databasesCompiled = "${WORKSPACE}/${tmp}/databases-compiled"
    def databasesInterpreted = "${WORKSPACE}/${tmp}/databases-interpreted"
    def supportedInterpretedLanguages = getInterpretedLanguages()
    def detectedInterpretedLanguages = supportedInterpretedLanguages.intersect(languages)
    def supportedCompiledLanguages = getCompiledLangauges()
    def detectedCompiledLanguages = supportedCompiledLanguages.intersect(languages)

    if (detectedCompiledLanguages && closure == null){
        logWarn('CodeQL requires the build to be placed inside a closure for compiled language identifiers! Compiled languages will not be scanned.')
        detectedCompiledLanguages = []
    }

    if (detectedCompiledLanguages && detectedInterpretedLanguages) {
        createTracedDatabases([codeqlDatabase: databasesCompiled, languages: detectedCompiledLanguages, ram: ram, threads: threads, verbosity: verbosity], closure)
        createStandardDatabases([codeqlDatabase: databasesInterpreted, languages:detectedInterpretedLanguages, ram: ram, threads: threads, verbosity: verbosity])
    } else if (detectedCompiledLanguages) {
        createTracedDatabases([codeqlDatabase: databasesCompiled, languages: detectedCompiledLanguages, ram: ram, threads: threads, verbosity: verbosity], closure)
    } else {
        createStandardDatabases([codeqlDatabase: databasesInterpreted, languages:detectedInterpretedLanguages, ram: ram, threads: threads, verbosity: verbosity])
    }

    def detectedLanguages = detectedCompiledLanguages + detectedInterpretedLanguages
    for (language in detectedLanguages) {
        def codeqlDatabase
        if (detectedCompiledLanguages.contains(language)) {
            codeqlDatabase = databasesCompiled
        } else {
            codeqlDatabase = databasesInterpreted
        }

        codeqlDatabase = "${codeqlDatabase}/${language}"
        def sarifResults = "${WORKSPACE}/${tmp}/results/${language}-results.sarif"
        analyze([codeqlDatabase: codeqlDatabase, querySuite: querySuite, category: language, sarifResults: sarifResults, ram: ram, threads: threads, verbosity: verbosity])
        uploadScanResults([sarifResults: sarifResults, org: org, repo: repo, ref: ref, commit: commit, verbosity: verbosity])
    }

    pwsh("Remove-Item ${WORKSPACE}/${tmp} -Recurse -Force")
}

def defaultIfNullOrEmtpy(val, defaultVal) {
    if (val == null || val == "") {
        return defaultVal
    }
    return val
}

def getCodeqlTempFolder() {
    return '.codeql-tmp'
}

def createCodeqlFolders() {
    def tmp = getCodeqlTempFolder()
    pwsh("""
        if (Test-Path -Path "${WORKSPACE}/${tmp}") {Remove-Item "${WORKSPACE}/${tmp}" -Recurse -Force | Out-Null}
        New-Item -ItemType Directory -Path "${WORKSPACE}/${tmp}/database" | Out-Null
        New-Item -ItemType Directory -Path "${WORKSPACE}/${tmp}/results" | Out-Null
        New-Item -ItemType Directory -Path "${WORKSPACE}/${tmp}/codeql" | Out-Null
    """)
}

def getCodeqlExecutable() {
    def tmp = getCodeqlTempFolder()
    return "${WORKSPACE}/${tmp}/codeql/codeql"
}

def codeqlInstall() {
    def tmp = getCodeqlTempFolder()
    withEnv(["tmp=${tmp}"]) {
        pwsh('''
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

            Invoke-RestMethod @splat -OutFile $env:WORKSPACE/$env:tmp/$bundleName
            tar -xzf $env:WORKSPACE/$env:tmp/$bundleName -C $env:WORKSPACE/$env:tmp
            Remove-Item $env:WORKSPACE/$env:tmp/$bundleName -Force
        ''')
    }
}

def getCompiledLangauges() {
    return ['cpp', 'csharp', 'go', 'java']
}

def getInterpretedLanguages() {
    return ['javascript', 'python', 'ruby']
}

def getSupportedLanguages() {
    def compiledLanguages = getCompiledLangauges()
    def interpretedLanguages = getInterpretedLanguages()
    return compiledLanguages + interpretedLanguages
}

def processLanguages(List<String> suppliedLanguages) {
    if (suppliedLanguages.isEmpty()) {
        logAndRaiseError("No languages were supplied in the 'languages' parameter. You must provide a list of languages.")
    }

    def languages = suppliedLanguages.collect { it.toLowerCase() }
    def supportedLanguages = getSupportedLanguages()
    def isEveryLanguageSupported = languages.every { supportedLanguages.contains(it) }
    
    if (!isEveryLanguageSupported) {
        logAndRaiseError("The following languages were specified: ${languages}. CodeQL currently supports ${supportedLanguages}")
    }

    return languages
}

def createTracedDatabases(Map params, Closure closure) {
    def codeqlDatabase = params['codeqlDatabase']
    def languages = params['languages']
    def ram = params['ram']
    def threads = params['threads']
    def verbosity = params['verbosity']
    def listOfLanguages = languages.join(",")
    def codeql = getCodeqlExecutable()

    pwsh("""
        \"${codeql}\" database init ${codeqlDatabase} \
            --source-root=. \
            --language=${listOfLanguages} \
            --begin-tracing \
            --db-cluster \
            --overwrite \
            --verbosity=${verbosity}
    """)

    def codeqlTracingScript = "${codeqlDatabase}/temp/tracingEnvironment/start-tracing.sh"
    def tracingScriptContent = pwsh(script: "Get-Content -Path ${codeqlTracingScript}", returnStdout: true).trim()
    def effectiveOverrides = tracingScriptContent
        .split('\n')
        .collect {
            line -> line.replace("export ", "")
                .replace("\'", "")
                .replace("\"", "")
        }
    
    withEnv(effectiveOverrides) {
        closure.call()
    }

    pwsh("""
        \"${codeql}\" database finalize ${codeqlDatabase} \
            --db-cluster \
            --ram=${ram} \
            --threads=${threads} \
            --verbosity=${verbosity}
    """)
}

def createStandardDatabases(Map params) {
    def codeqlDatabase = params['codeqlDatabase']
    def languages = params['languages']
    def ram = params['ram']
    def threads = params['threads']
    def verbosity = params['verbosity']
    def listOfLanguages = languages.join(",")
    def codeql = getCodeqlExecutable()

    pwsh("""
        \"${codeql}\" database create ${codeqlDatabase} \
            --language=${listOfLanguages} \
            --db-cluster \
            --overwrite \
            --ram=${ram} \
            --threads=${threads} \
            --verbosity=${verbosity}
    """)
}

def analyze(Map params) {
    def codeqlDatabase = params['codeqlDatabase']
    def querySuite = params['querySuite']
    def category = params['category']
    def sarifResults = params['sarifResults']
    def ram = params['ram']
    def threads = params['threads']
    def verbosity = params['verbosity']
    def codeql = getCodeqlExecutable()
    def queries = pwsh(script:"(Get-ChildItem -Recurse -Filter '*${category}-${querySuite}.qls').FullName", returnStdout: true).trim()

    pwsh("""
        \"${codeql}\" database analyze ${codeqlDatabase} ${queries} \
            --format=sarif-latest \
            --sarif-category=${category} \
            --output=${sarifResults} \
            --ram=${ram} \
            --threads=${threads} \
            --verbosity=${verbosity}
    """)
}

def uploadScanResults(Map params) {
    def sarifResults = params['sarifResults']
    def org = params['org']
    def repo = params['repo']
    def ref = params['ref']
    def commit = params['commit']
    def verbosity = params['verbosity']
    def codeql = getCodeqlExecutable()
    def repository = org + '/' + repo
    
    dir("${WORKSPACE}") {
        pwsh("""
            \"${codeql}\" github upload-results \
                --sarif=${sarifResults} \
                --ref=${ref} \
                --repository=${repository} \
                --commit=${commit} \
                --verbosity=${verbosity}"""
        )
    }
}

def processRef(String suppliedRef) {
    def ref

    if (suppliedRef) {
        if (suppliedRef ==~ /(^refs\/(heads|tags)\/.*)|(^refs\/pull\/\d+\/(merge|head))/) {
            ref = suppliedRef
        } else {
            logAndRaiseError("Supplied ref '${suppliedRef}' does not match expected formats:\n'refs/heads/<branch name>'\n'refs/tags/<tag name>'\n'refs/pull/<number>/merge'\n'refs/pull/<number>/head'")
        }
    } else {
        ref = pwsh(script:'git symbolic-ref HEAD', returnStdout: true).trim()
    }

    return ref
}

def logAndRaiseError(message, String ... parameters) {
    def messageParameters = ['ERROR']
    messageParameters.addAll(parameters)
    def sanitizedInput = messageParameters.collect { it.replaceAll("%","%%") }
    error(sprintf("[%s] ${message}", sanitizedInput))
}
