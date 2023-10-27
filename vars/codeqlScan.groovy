def call (Map params, Closure closure = null) {
    def languages = processLanguages(params['languages'])
    def ref = processRef(params['ref'])
    def ram = defaultIfNullOrEmtpy(params['ram'] as Integer, 4000)
    def threads = defaultIfNullOrEmtpy(params['threads'] as Integer, 1)
    def verbosity = defaultIfNullOrEmtpy(params['verbosity'], 'errors')
    def querySuite = defaultIfNullOrEmtpy(params['querySuite'], 'code-scanning')
    def origin = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
    def org = origin.tokenize('/')[-2]
    def repo = origin.tokenize('/')[-1].tokenize('.')[0]
    def commit = sh(script: 'git rev-parse --verify HEAD', returnStdout: true).trim()

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

    sh("rm -rf ${WORKSPACE}/${tmp}")
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
    sh("""
        rm -rf ${WORKSPACE}/${tmp}
        mkdir -p ${WORKSPACE}/${tmp}/database
        mkdir -p ${WORKSPACE}/${tmp}/results
        mkdir -p ${WORKSPACE}/${tmp}/codeql
    """)
}

def getCodeqlExecutable() {
    def tmp = getCodeqlTempFolder()
    return "${WORKSPACE}/${tmp}/codeql/codeql"
}

def codeqlInstall() {
    def tmp = getCodeqlTempFolder()
    def os
    def uname = sh(script: "uname", returnStdout: true).trim()
    if (uname.startsWith("Darwin")) {os = 'osx'} else {os = 'linux'}
    def tar = "codeql-bundle-${os}64.tar.gz"
    def codeqlReleaseUrl = "https://github.com/github/codeql-action/releases/download/codeql-bundle-v2.15.1/${tar}"
    def codeqlArchivePath = "${WORKSPACE}/${tmp}/codeql-bundle-linux64.tar.gz"
    sh("curl -s -o ${codeqlArchivePath} -L ${codeqlReleaseUrl}")
    def codeqlPath = "${WORKSPACE}/${tmp}"
    sh("tar -xzf ${codeqlArchivePath} -C ${codeqlPath}")
    def codeql = getCodeqlExecutable()
    sh(script:"${codeql} --version", returnStdout: true).trim()
    sh("rm -rf ${codeqlArchivePath}")
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

    sh("""
        ${codeql} database init ${codeqlDatabase} \
            --source-root=. \
            --language=${listOfLanguages} \
            --begin-tracing \
            --db-cluster \
            --overwrite \
            --verbosity=${verbosity}
    """)

    def codeqlTracingScript = "${codeqlDatabase}/temp/tracingEnvironment/start-tracing.sh"
    def tracingScriptContent = sh(script: "cat ${codeqlTracingScript}", returnStdout: true).trim()
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

    sh("""
        ${codeql} database finalize ${codeqlDatabase} \
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

    sh("""
        ${codeql} database create ${codeqlDatabase} \
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
    def tmp = getCodeqlTempFolder()
    def queries = sh(script:"find ${WORKSPACE}/{tmp} -type f -iname ${category}-${querySuite}.qls", returnStdout: true).trim()

    sh("""
        ${codeql} database analyze ${codeqlDatabase} ${queries} \
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
        sh("""
            ${codeql} github upload-results \
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
        ref = sh(script:'git symbolic-ref HEAD', returnStdout: true).trim()
    }

    return ref
}

def logAndRaiseError(message, String ... parameters) {
    def messageParameters = ['ERROR']
    messageParameters.addAll(parameters)
    def sanitizedInput = messageParameters.collect { it.replaceAll("%","%%") }
    error(sprintf("[%s] ${message}", sanitizedInput))
}
