# Publish Nerqo to GitHub

1. Create a **new, empty repository** named `Nerqo` under `R3Dzf` at https://github.com/new. Keep **Add a README**, **Add .gitignore**, and **Choose a license** unselected, because this source already has a README and .gitignore.
2. Either return to ChatGPT after creating it, so the connected GitHub integration can publish the files, **or** run the following commands from inside the extracted `Nerqo/` directory on a computer with Git installed and GitHub authentication configured:

```bash
git init -b main
git add .
git commit -m "feat: initial Nerqo offline sharing prototype"
git remote add origin https://github.com/R3Dzf/Nerqo.git
git push -u origin main
```

An optional `.bundle` containing the prepared first commit is also provided separately; to use it, run:

```bash
git clone Nerqo-v0.1.0.git.bundle Nerqo
cd Nerqo
git remote remove origin
git remote add origin https://github.com/R3Dzf/Nerqo.git
git push -u origin main
```

Do not upload real transfer histories, `.env` files, secrets, keystores, or local Android SDK configuration.
