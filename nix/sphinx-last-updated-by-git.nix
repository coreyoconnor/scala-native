{
  lib,
  buildPythonPackage,
  fetchPypi,
  sphinx,
}:

buildPythonPackage rec {
  pname = "sphinx_last_updated_by_git";
  version = "0.3.7";
  format = "setuptools";

  src = fetchPypi {
    inherit pname version;
    hash = "sha256-7f1JcNl3gSPT0NnFyanj1wCGeggFOesr/gHnB4yh3Hg=";
  };

  propagatedBuildInputs = [
    sphinx
  ];

  pythonImportsCheck = [ "sphinx_last_updated_by_git" ];
}
