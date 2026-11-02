Relatório de Análise de Segurança — S-File Encryptor
Tipo de análise: Análise estática de código-fonte
Projeto: S-File Encryptor
Versão analisada: 2.x
Plataforma: Android (Java)
Data da análise: Novembro de 2025
Arquivos avaliados: MainActivity.java, CryptoEngine.java e BiometricPasswordStore.java
Responsável pela análise: IA Assistente de Código
1. Resumo Executivo

Este documento apresenta uma avaliação das implementações de segurança do S-File Encryptor, considerando os três arquivos-fonte disponibilizados para análise.

A avaliação contempla mecanismos criptográficos, gerenciamento de senhas e chaves, autenticação biométrica, manipulação de arquivos, proteção contra entradas maliciosas e práticas gerais de segurança no Android.

Indicadores da análise
Indicador	Resultado
Vulnerabilidades críticas identificadas	0
Boas práticas identificadas	15+
Observações de melhoria	4
Classificação preliminar	Favorável
Veredito preliminar

Não foram identificadas vulnerabilidades críticas nos trechos analisados.

O aplicativo apresenta uma arquitetura de segurança consistente, com destaque para a utilização de Argon2id, AES-256-GCM, Android Keystore e mecanismos de proteção contra manipulação de arquivos.

A implementação do formato V2 apresenta escolhas criptográficas modernas e boas práticas de gerenciamento de memória.

Entretanto, esta avaliação não representa uma certificação de segurança nem comprova a ausência de vulnerabilidades. Testes dinâmicos, análise das dependências, revisão do fluxo completo de execução e auditoria independente são necessários para uma conclusão definitiva sobre a segurança do aplicativo.

2. Arquitetura do Sistema
2.1 Componentes principais

O aplicativo apresenta três componentes principais:

Componente	Responsabilidade
MainActivity	Interface do usuário e gerenciamento do fluxo de operações.
CryptoEngine	Implementação dos mecanismos criptográficos e gerenciamento dos formatos V1 e V2.
BiometricPasswordStore	Armazenamento protegido de credenciais e integração com o Android Keystore.

A separação dessas responsabilidades contribui para a organização do código e permite aplicar diferentes mecanismos de proteção em cada camada.

2.2 Fluxo de dados
text
┌─────────────────────┐
│      UI Layer       │
│    MainActivity     │
└──────────┬──────────┘
           │
           │ Senha / Biometria
           ▼
┌─────────────────────┐
│     Auth Layer      │
│ BiometricPassword   │
│       Store         │
└──────────┬──────────┘
           │
           │ Credencial recuperada
           ▼
┌─────────────────────┐
│    Crypto Layer     │
│    CryptoEngine     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Arquivo criptografado│
└─────────────────────┘

3. Pontos Fortes Identificados
3.1 Criptografia V2

O formato V2 utiliza mecanismos criptográficos modernos:

Componente	Implementação	Avaliação
KDF	Argon2id	Boa resistência a ataques de tentativa de senha.
Memória Argon2	64 MiB	Custo computacional e de memória relevante.
Iterações	3	Parâmetro configurado no formato analisado.
Paralelismo	1 lane	Configuração conservadora.
Cifra	AES-256-GCM	Criptografia autenticada (AEAD).
Tag de autenticação	128 bits	Integridade e autenticidade dos dados.
Salt	16 bytes aleatórios	Reduz reutilização de material derivado.
Nonce	12 bytes	Tamanho recomendado para GCM.
AAD	Header autenticado	Protege a integridade dos metadados.

Análise técnica:

Argon2id combina características das variantes Argon2i e Argon2d, oferecendo resistência a diferentes classes de ataques contra senhas.

A utilização de AES-256-GCM permite combinar confidencialidade e autenticação em uma única operação criptográfica.

A segurança efetiva depende da correta implementação, da geração de parâmetros, da validação dos metadados e da garantia de que nonces não sejam reutilizados com a mesma chave.

3.2 Gerenciamento e Higienização de Memória

Foi identificada a utilização de Arrays.fill() para limpar buffers que podem conter dados sensíveis.

Exemplo:

java
Arrays.fill(passwordBytes, (byte) 0);
Arrays.fill(buffer, (byte) 0);
Arrays.fill(salt, (byte) 0);
Arrays.fill(nonce, (byte) 0);
Arrays.fill(key, (byte) 0);


Análise técnica:

A limpeza explícita de arrays reduz o tempo durante o qual informações sensíveis permanecem disponíveis na memória.

Outro ponto positivo é a preferência por arrays de bytes em vez de objetos String para o tratamento de senhas.

Entretanto, a limpeza de memória em Java não oferece garantia absoluta de eliminação de todas as cópias dos dados, devido ao gerenciamento automático de memória e às possíveis otimizações da JVM.

3.3 Proteção Contra Captura de Tela

Foi identificada a utilização de FLAG_SECURE:

java
getWindow().setFlags(
    WindowManager.LayoutParams.FLAG_SECURE,
    WindowManager.LayoutParams.FLAG_SECURE
);


Benefícios:

Restringe capturas de tela convencionais.
Impede a inclusão da janela em determinadas gravações de tela.
Reduz a exposição visual de informações sensíveis.

A proteção depende do comportamento do sistema operacional e não impede todos os métodos possíveis de captura de informações.

3.4 Proteção Contra Path Traversal (Zip Slip)

Foi identificada uma rotina de validação dos caminhos presentes em arquivos compactados.

Exemplo simplificado:

java
private boolean isSafeZipPath(String path) {
    if (path.startsWith("/") || path.indexOf('\\') >= 0) {
        return false;
    }

    // Validação dos segmentos do caminho.
    // Rejeição de segmentos "." e "..".
    // Rejeição de caminhos absolutos.
    // Rejeição de caracteres de controle.

    return true;
}


Análise técnica:

A validação busca impedir que entradas maliciosas de arquivos ZIP utilizem caminhos relativos para acessar diretórios fora do destino autorizado.

Esse mecanismo é relevante para mitigar ataques conhecidos como Zip Slip.

A segurança depende também da normalização dos caminhos e da validação do destino final após a resolução do caminho.

3.5 Autenticação Biométrica

Foram identificadas as seguintes configurações:

java
.setUserAuthenticationRequired(true)
.setUserAuthenticationParameters(
    0,
    AUTH_BIOMETRIC_STRONG
)
.setInvalidatedByBiometricEnrollment(true)


Proteções oferecidas:

Exigência de autenticação para utilização da chave.
Integração com o Android Keystore.
Restrição ao uso de biometria forte.
Possibilidade de invalidação da chave após alterações no cadastro biométrico.

A implementação utiliza mecanismos nativos do Android para controlar o acesso às operações criptográficas.

A proteção efetiva depende do hardware, da versão do Android e da configuração do dispositivo.

3.6 Limites Contra Consumo Excessivo de Recursos

Foram identificados limites para operações envolvendo arquivos e parâmetros criptográficos.

Recurso	Limite identificado
Tamanho total do ZIP	2 GB
Tamanho por entrada	1 GB
Quantidade de entradas	10.000
Profundidade de diretórios	20 níveis
Memória Argon2	8–128 MiB
Iterações Argon2	1–6

Análise técnica:

A imposição de limites reduz o risco de consumo excessivo de memória, processamento e armazenamento durante a manipulação de arquivos.

Essas restrições também contribuem para a mitigação de ataques de negação de serviço baseados em arquivos malformados ou excessivamente grandes.

3.7 Integridade Criptográfica no Formato V1

O formato legado V1 utiliza mecanismos de autenticação baseados em HMAC-SHA256.

Foram identificadas as seguintes características:

Autenticação dos dados criptografados.
Comparação de MAC utilizando MessageDigest.isEqual().
Verificação de integridade antes da descriptografia.
Separação entre autenticação e recuperação dos dados.

Essa abordagem é importante para evitar que dados modificados sejam processados como arquivos válidos.

3.8 Proteção Contra Escrita de Dados Não Autenticados no V2

Foi identificada a utilização de arquivos temporários durante o processamento de dados protegidos por AES-GCM.

Fluxo simplificado:

java
File tempFile = createPrivateTempFile(
    "sfe-v2-",
    ".tmp",
    tempDirectory
);

// Processamento criptográfico.

// A autenticação é confirmada após doFinal().

// Somente então os dados são encaminhados
// ao destino definitivo.

copyToOutput(tempFile, outputUri);

// Remoção do arquivo temporário.
tempFile.delete();


Análise técnica:

O AES-GCM confirma a autenticidade do conteúdo durante a finalização da operação criptográfica.

O uso de um arquivo temporário evita disponibilizar diretamente no destino final dados cuja autenticidade ainda não foi confirmada.

Também é importante garantir que arquivos temporários sejam removidos mesmo em situações de exceção ou interrupção inesperada.

3.9 Utilização do Scoped Storage

Foram identificadas práticas alinhadas ao modelo moderno de armazenamento do Android:

Utilização de ACTION_OPEN_DOCUMENT_TREE.
Uso de takePersistableUriPermission().
Redução da necessidade de permissões amplas de armazenamento.
Utilização do Storage Access Framework.

Essas práticas reduzem a superfície de acesso ao armazenamento e favorecem o isolamento entre aplicativos.

3.10 Tratamento de Exceções

Foi identificada uma separação entre diferentes tipos de erros:

java
catch (WrongPasswordException e) {
    cleanup(tempInput, tempZip, tempOutput);
    deleteOutputQuietly();
    fail(R.string.wrong_password);

} catch (CorruptFileException e) {
    // Tratamento de arquivo corrompido.

} catch (Exception e) {
    // Tratamento genérico.
}


Análise técnica:

A utilização de mensagens genéricas na interface reduz a exposição de detalhes internos da implementação.

A diferenciação de exceções também facilita o tratamento de erros e a manutenção do código.

4. Observações e Oportunidades de Melhoria

As observações a seguir representam oportunidades de endurecimento da segurança. Não devem ser interpretadas automaticamente como vulnerabilidades exploráveis.

4.1 PBKDF2 com 100.000 Iterações no Formato V1

Trecho identificado:

java
private static final int V1_ITERATIONS = 100_000;


Contexto:

O parâmetro de 100.000 iterações é inferior às recomendações atuais da OWASP para PBKDF2-HMAC-SHA256, que indicam valores significativamente maiores.

Impacto: Baixo, considerando que o formato V1 é destinado à compatibilidade com arquivos antigos e não à geração de novos arquivos.

Recomendação:

Manter o suporte de leitura do formato V1 e incentivar a migração para o formato V2.

Sugestão de mensagem:

Formato legado detectado. Para utilizar os mecanismos criptográficos mais recentes, recomenda-se recriptografar este arquivo utilizando o formato V2.
4.2 Ausência de Detecção de Root

Cenário de risco:

Dispositivos com acesso root podem apresentar maior exposição a:

Instrumentação de processos.
Interceptação de chamadas.
Manipulação de dados em memória.
Modificação do ambiente de execução.

Mitigação existente:

O Android Keystore pode oferecer proteção adicional por meio de TEE ou StrongBox, quando disponíveis e corretamente configurados.

Recomendação:

Considerar mecanismos de avaliação da integridade do dispositivo, principalmente caso o aplicativo seja direcionado a ambientes corporativos ou de maior sensibilidade.

É importante observar que a detecção de root pode ser contornada e não deve ser considerada uma garantia de segurança.

4.3 Ausência de Verificação de Integridade da Assinatura em Runtime

Cenário de risco:

Uma versão modificada do aplicativo pode apresentar alterações no comportamento esperado, incluindo:

Modificações no fluxo de autenticação.
Alterações no processamento criptográfico.
Inclusão de funcionalidades não autorizadas.

Mitigação existente:

A assinatura digital do APK permite verificar a identidade do signatário e a integridade do pacote instalado.

Recomendação:

Avaliar mecanismos de verificação de integridade da aplicação, especialmente em distribuições fora das lojas oficiais.

A verificação local de assinatura deve ser considerada uma camada adicional, não uma defesa definitiva contra adulteração.

4.4 Ausência de Detecção de Emulador

Contexto:

Emuladores podem ser utilizados em atividades de engenharia reversa, testes automatizados e análise de aplicações.

Impacto: Baixo para utilização pessoal.

Recomendação:

Considerar a identificação do ambiente de execução caso exista uma necessidade específica de restringir operações sensíveis em determinados ambientes.

A execução em emuladores não representa, por si só, uma condição insegura.

5. Checklist de Boas Práticas
5.1 Criptografia
Item	Status	Observação
Algoritmo moderno	Implementado	AES-256-GCM
KDF resistente a ataques de GPU	Implementado	Argon2id
Salt aleatório	Implementado	16 bytes
Nonce de tamanho adequado	Implementado	12 bytes
Autenticação de dados	Implementado	GCM Tag e AAD
Compatibilidade com formato legado	Implementado	V1
Proteção contra manipulação do header	Implementado	Autenticação AAD no V2
5.2 Gerenciamento de Senhas
Item	Status	Observação
Limpeza de memória	Implementado	Arrays.fill()
Evitar objetos String para senhas	Implementado	Preferência por arrays de bytes
Armazenamento protegido	Implementado	Android Keystore
Timeout automático	A avaliar	Melhoria opcional
Política de complexidade	Implementado	Mínimo informado de 12 caracteres
5.3 Controle de Acesso
Item	Status	Observação
Autenticação biométrica	Implementado	BiometricPrompt
Integração com CryptoObject	Implementado	Conforme análise
Proteção contra screenshots	Implementado	FLAG_SECURE
Detecção de root	Não implementado	Opcional
Verificação de assinatura em runtime	Não implementado	Opcional
5.4 Manipulação de Arquivos
Item	Status	Observação
Proteção Zip Slip	Implementado	Validação de caminhos
Limites de recursos	Implementado	Tamanho, quantidade e profundidade
Arquivos temporários privados	Implementado	Conforme análise
Scoped Storage	Implementado	Storage Access Framework
Limpeza de temporários	Implementado	Rotinas de limpeza
5.5 Interface e Experiência de Uso
Item	Status	Observação
Feedback durante operações	Implementado	ProgressBar
Mensagens de erro genéricas	Implementado	Conforme análise
Tratamento de exceções	Implementado	Blocos try/catch
Logout ou bloqueio por inatividade	A avaliar	Melhoria opcional
6. Recomendações de Endurecimento
6.1 Melhorias já contempladas na implementação
Criptografia moderna no formato V2.
Utilização de Argon2id.
Criptografia autenticada AES-256-GCM.
Integração com Android Keystore.
Limpeza explícita de buffers sensíveis.
Validação de caminhos de arquivos compactados.
Limites para consumo de recursos.
Utilização de arquivos temporários privados.
Autenticação biométrica.
6.2 Melhorias futuras
Avaliar mecanismos de integridade do dispositivo.
Avaliar verificação de assinatura do aplicativo.
Implementar bloqueio automático após período de inatividade.
Informar usuários sobre arquivos no formato V1.
Avaliar parâmetros Argon2 para dispositivos com pouca memória.
Considerar detecção de emuladores conforme o modelo de distribuição.
Avaliar ativação e configuração do R8 em builds de produção.
Publicar documentação técnica do formato V2.
Adicionar testes automatizados de criptografia e integridade.
Realizar testes com arquivos malformados e entradas adversariais.
7. Comparativo Técnico

A tabela abaixo apresenta uma comparação qualitativa entre características de segurança encontradas em diferentes categorias de aplicativos.

Os valores representam referências gerais de implementação, não resultados de uma pesquisa estatística sobre produtos comerciais específicos.

Característica	S-File Encryptor	Implementações básicas	Implementações avançadas
KDF	Argon2id	PBKDF2 com parâmetros variáveis	Argon2id ou equivalente
Cifra	AES-256-GCM	AES-CBC ou outras	AES-GCM ou equivalente
Salt	16 bytes aleatórios	Variável	Aleatório
Autenticação	GCM Tag + AAD	Variável	AEAD
Higiene de memória	Limpeza explícita	Variável	Variável
Biometria	Android Keystore	Variável	Android Keystore
Scoped Storage	Sim	Variável	Sim
Código-fonte aberto	Sim	Variável	Variável

Conclusão comparativa:

O S-File Encryptor apresenta escolhas técnicas compatíveis com implementações modernas de armazenamento criptografado.

Entretanto, não é possível afirmar superioridade sobre aplicativos comerciais sem avaliações equivalentes, testes independentes e análise de suas implementações internas.

8. Notas Técnicas Complementares
8.1 Estrutura do Formato V2

O formato V2 utiliza um header de 48 bytes.

Offset	Tamanho	Campo	Descrição
0–3	4 bytes	Magic	Identificador SFE2
4	1 byte	Version	Versão do formato
5	1 byte	KDF	Identificador do Argon2id
6	1 byte	Cipher	Identificador do AES-256-GCM
7	1 byte	Reserved	Reservado para expansão
8–11	4 bytes	Memory	Parâmetro de memória em KiB
12–15	4 bytes	Iterations	Quantidade de iterações
16–19	4 bytes	Parallelism	Paralelismo
20–35	16 bytes	Salt	Salt aleatório
36–47	12 bytes	Nonce	Nonce do AES-GCM

Após o header:

text
┌──────────────────────────────┐
│        Header V2             │
│          48 bytes            │
├──────────────────────────────┤
│                              │
│        Ciphertext            │
│                              │
├──────────────────────────────┤
│        GCM Authentication    │
│           Tag                │
│          16 bytes            │
└──────────────────────────────┘


Extensibilidade:

A utilização de identificadores para KDF e cifra permite planejar futuras versões do formato sem depender exclusivamente dos parâmetros atuais.

É importante manter validações rigorosas dos identificadores e dos parâmetros recebidos antes de iniciar operações criptográficas.

8.2 Integração Biométrica

O fluxo identificado pode ser resumido da seguinte forma.

Cadastro:

O usuário seleciona a opção de habilitar autenticação biométrica.
O aplicativo solicita autenticação inicial.
Uma chave protegida pelo Android Keystore é utilizada para criptografar a senha.
O conteúdo criptografado e os metadados necessários são armazenados em SharedPreferences.
A chave privada permanece sob controle do Android Keystore.

Utilização:

O aplicativo solicita uma operação de descriptografia utilizando a chave protegida.
O usuário realiza a autenticação biométrica.
O Android autoriza a utilização do objeto criptográfico.
A senha armazenada é recuperada em formato descriptografado.
A credencial é utilizada na operação solicitada.
Os buffers utilizados são limpos após o processamento.

Observação de segurança:

O armazenamento da senha criptografada em SharedPreferences não equivale ao armazenamento em texto claro.

Entretanto, a proteção depende da configuração correta do Keystore, das políticas de autenticação e do tratamento dos dados em memória.

8.3 Considerações de Performance

As estimativas abaixo são referências indicativas, não resultados de benchmarks executados durante esta análise.

Operação	Dispositivo intermediário (4 GB RAM)	Dispositivo básico (2 GB RAM)
Argon2id (64 MiB)	300–500 ms	800–1500 ms
Criptografia de 100 MB	1–2 s	3–5 s
Descriptografia de 100 MB	1–2 s	3–5 s
Cadastro biométrico	200 ms	400 ms

O desempenho real depende do processador, da memória disponível, da implementação da biblioteca criptográfica, da velocidade do armazenamento e das condições do sistema operacional.

Recomenda-se realizar benchmarks em dispositivos reais antes de modificar os parâmetros criptográficos.

9. Vetores de Ataque e Mecanismos de Proteção

A tabela apresenta os vetores considerados durante a análise e os mecanismos de defesa identificados.

Vetor	Avaliação	Mecanismo identificado
Brute-force offline	Mitigado	Argon2id com custo de memória e processamento
Brute-force online	Não avaliado integralmente	Depende de controles adicionais de autenticação
Timing attacks	Parcialmente mitigado	Comparação de MAC em tempo constante
Memory scraping	Mitigado parcialmente	Limpeza de buffers e redução do tempo de exposição
Extração de chaves	Mitigado	Android Keystore e chaves não exportáveis, conforme configuração
Reutilização de nonce	Dependente da implementação	Geração de nonce aleatório
Zip Slip	Mitigado	Validação de caminhos
DoS por arquivos grandes	Mitigado	Limites de tamanho e quantidade
Inspeção de heap	Mitigado parcialmente	Limpeza de memória
Bypass biométrico	Mitigado	Autenticação exigida pelo Keystore
Clonagem de Keystore	Mitigado	Proteção vinculada ao dispositivo e ao hardware, quando disponível

Nota: Mitigação não significa eliminação absoluta do risco. A eficácia de cada mecanismo precisa ser confirmada por testes específicos.

10. Classificação Final

As notas representam uma avaliação qualitativa baseada exclusivamente nos componentes disponibilizados.

Categoria	Nota	Comentário
Criptografia	9,5/10	Utilização de Argon2id e AES-256-GCM
Gerenciamento de chaves	9,0/10	Integração com Android Keystore
Higiene de memória	10,0/10	Limpeza explícita de buffers
UX de segurança	8,5/10	Possibilidade de melhorias no bloqueio por inatividade
Defesas de ambiente	6,0/10	Ausência de mecanismos adicionais de integridade
Média geral	8,6/10	Avaliação qualitativa favorável
Veredito

O S-File Encryptor apresenta uma base técnica consistente de segurança para um aplicativo de criptografia de arquivos.

Os principais destaques são:

Utilização de algoritmos criptográficos modernos.
Derivação de chaves com Argon2id.
Criptografia autenticada com AES-256-GCM.
Integração com Android Keystore.
Cuidados com o ciclo de vida de dados sensíveis em memória.
Validação de arquivos e caminhos.
Limites para consumo de recursos.

Não foram identificadas vulnerabilidades críticas nos arquivos disponibilizados para análise.

As melhorias sugeridas estão relacionadas principalmente ao endurecimento do ambiente de execução, à integridade da aplicação e à experiência de segurança.

Classificação preliminar: favorável, condicionada à realização de testes complementares.

11. Referências Técnicas
OWASP Cryptographic Storage Cheat Sheet
OWASP Password Storage Cheat Sheet
OWASP Authentication Cheat Sheet
NIST SP 800-132 — Password-Based Key Derivation
RFC 9106 — Argon2 Memory-Hard Function
Android Security Best Practices
Android Keystore System
Bouncy Castle Documentation
12. Limitações da Análise

Este relatório foi elaborado com base na análise estática de três arquivos Java:

MainActivity.java
CryptoEngine.java
BiometricPasswordStore.java

Não foram realizados, no escopo desta avaliação:

Testes dinâmicos em dispositivos Android.
Testes de penetração.
Análise de bibliotecas e dependências de terceiros.
Auditoria completa do manifesto Android.
Análise de configurações de build e assinatura.
Testes automatizados de criptografia.
Verificação independente de todas as alegações de segurança.
Auditoria formal do formato de arquivos.

Consequentemente, os resultados não devem ser interpretados como garantia de ausência de vulnerabilidades.

Uma auditoria profissional independente é recomendada antes da utilização do aplicativo em ambientes de alta sensibilidade.

Relatório gerado com auxílio de IA, com base na análise estática do código-fonte disponibilizado. As conclusões refletem o escopo e as informações disponíveis no momento da avaliação.

Última revisão editorial: Novembro de 2026.
