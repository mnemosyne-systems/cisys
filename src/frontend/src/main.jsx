/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import Keycloak from 'keycloak-js';
import { FolderGit2, LockKeyhole, LockKeyholeOpen, Moon, Sun, LogOut, ChevronDown, CircleUserRound, Plus, Trash2, UserRound } from 'lucide-react';
import { Button } from './components/ui/button';
import { Input } from './components/ui/input';
import { Label } from './components/ui/label';
import { Badge } from './components/ui/badge';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from './components/ui/dropdown-menu';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './components/ui/card';
import AppFooter from './components/layout/AppFooter';
import PageHeader from './components/layout/PageHeader';
import BrandingPanel from './BrandingPanel';
import ImagesPanel from './ImagesPanel';
import SettingsPanel from './SettingsPanel';
import PublicHome from './PublicHome';
import PublicStats from './PublicStats';
import ProjectBuilds, { projectBuildFromPath, projectNameFromPath, projectPath } from './ProjectBuilds';
import ProjectBuild from './ProjectBuild';
import { buildPath } from './build-format';
import headerLogo from './assets/logo-header.svg';
import './style.css';

const API = 'http://localhost:8080/api';
const keycloak = new Keycloak({ url: 'http://localhost:8180', realm: 'cisys', clientId: 'cisys-ui' });
// Matches the Branding entity defaults, used when the public branding cannot be loaded.
const DEFAULT_BRANDING = { name: 'cisys', headerColor: '#176a4a', headingColor: '#176a4a', buttonColor: '#176a4a' };

async function publicJson(path, opts = {}) {
  const response = await fetch(API + path);
  if (!response.ok) throw Object.assign(new Error(`Request failed (${response.status})`), { status: response.status });
  if (opts.raw) return response;
  return response.json();
}

async function api(path, opts = {}) {
  try {
    await keycloak.updateToken(30);
  } catch {
    // The session is gone, for example because the application was restarted.
    await keycloak.login();
    throw new Error('Your session has ended. Please sign in again.');
  }
  const response = await fetch(API + path, {
    ...opts,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + keycloak.token, ...opts.headers },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    if (body?.error) throw Object.assign(new Error(body.error), { status: response.status });
  }
  if (!response.ok) throw Object.assign(new Error(response.status === 429 ? 'The build queue is full. Try again later.' : `Request failed (${response.status}). Check the branch, image and project access.`), { status: response.status });
  if (opts.raw) return response;
  if (response.status === 204) return null;
  return response.json();
}

// Command used by the Build button on a project page's branch list.
const DEFAULT_BUILD_COMMAND = 'echo Hello from Cisys; ls';

function App() {
  // 'loading' until Keycloak answers, then 'public' for anonymous visitors or 'app' for signed-in users.
  const [mode, setMode] = useState('loading');
  const [stats, setStats] = useState(undefined);
  const [statsError, setStatsError] = useState('');
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState(null);
  const [projects, setProjects] = useState([]);
  const [error, setError] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [priv, setPriv] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [images, setImages] = useState([]);
  const [branding, setBranding] = useState(null);
  const [page, setPage] = useState('projects');
  // Set when the URL is a project page such as /cisys/ or a build such as /cisys/master/build/51.
  const [projectName, setProjectName] = useState(() =>
    projectBuildFromPath(window.location.pathname)?.name ?? projectNameFromPath(window.location.pathname));
  const [projectBranch, setProjectBranch] = useState(() => projectBuildFromPath(window.location.pathname)?.branch ?? '');
  const [buildId, setBuildId] = useState(() => projectBuildFromPath(window.location.pathname)?.buildId ?? null);
  const [dark, setDark] = useState(() => localStorage.getItem('cisys-theme') === 'dark');

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    localStorage.setItem('cisys-theme', dark ? 'dark' : 'light');
  }, [dark]);

  useEffect(() => {
    if (!branding) return;
    const root = document.documentElement.style;
    root.setProperty('--header-bg', branding.headerColor);
    root.setProperty('--section-header-color', branding.headingColor);
    root.setProperty('--button-bg', branding.buttonColor);
    root.setProperty('--section-header-hover', `color-mix(in srgb, ${branding.headingColor}, black 20%)`);
    root.setProperty('--button-hover', `color-mix(in srgb, ${branding.buttonColor}, black 20%)`);
    const contrast = (hex) => {
      const rgb = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
      return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 0.179 ? '#111111' : '#ffffff';
    };
    root.setProperty('--header-text', contrast(branding.headerColor));
    root.setProperty('--button-text', contrast(branding.buttonColor));
    document.body.style.fontFamily = branding.fontFamily === 'system' ? 'system-ui, sans-serif' : '"Red Hat Text", sans-serif';
    document.title = branding.name;
  }, [branding]);

  // Loads the public statistics for the public page and the signed-in home page.
  // Numbers already on screen stay visible while refreshing; a failure replaces them with an error.
  const loadPublicStats = useCallback(async () => {
    try {
      setStats(await publicJson('/stats/public'));
      setStatsError('');
    } catch {
      setStats(null);
      setStatsError('Statistics are unavailable right now.');
    }
  }, []);

  useEffect(() => {
    const showPublic = async () => {
      setMode('public');
      const loadBranding = publicJson('/branding/public')
        .then((value) => setBranding({ ...DEFAULT_BRANDING, ...value }))
        .catch(() => setBranding(DEFAULT_BRANDING));
      await Promise.allSettled([loadBranding, loadPublicStats()]);
    };
    const showApp = async () => {
      setMode('app');
      try {
        setUser(await api('/session'));
        setBranding(await api('/branding'));
        setProjects(await api('/projects'));
        setImages(await api('/images'));
        setReady(true);
      } catch (e) {
        setError(e.message || 'Unable to load Cisys');
      }
    };
    keycloak.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false })
      .then((authenticated) => (authenticated ? showApp() : showPublic()))
      // Keycloak is unreachable: still show the public page; Sign in retries the login.
      .catch(() => showPublic());
  }, [loadPublicStats]);

  useEffect(() => {
    const onPopState = () => {
      const build = projectBuildFromPath(window.location.pathname);
      setBuildId(build ? build.buildId : null);
      setProjectBranch(build ? build.branch : '');
      setProjectName(build ? build.name : projectNameFromPath(window.location.pathname));
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  // Switches to an in-app page, leaving any project URL behind.
  function go(target) {
    if (window.location.pathname !== '/') window.history.pushState(null, '', '/');
    setProjectName(null);
    setProjectBranch('');
    setBuildId(null);
    setPage(target);
  }

  function openProject(name, branch = '') {
    window.history.pushState(null, '', projectPath(name));
    setProjectName(name);
    setProjectBranch(branch);
    setBuildId(null);
  }

  function openBuild(name, branch, id) {
    window.history.pushState(null, '', buildPath(name, branch, id));
    setProjectName(name);
    setProjectBranch(branch);
    setBuildId(id);
  }

  async function create(event) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await api('/projects', { method: 'POST', body: JSON.stringify({ name, repositoryUrl: url, privateRepository: priv }) });
      setName(''); setUrl(''); setPriv(false);
      setProjects(await api('/projects'));
    } catch (e) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function deleteProject(project) {
    if (!window.confirm(`Delete ${project.name} and all of its builds? This cannot be undone.`)) return;
    setError('');
    try {
      await api(`/projects/${project.id}`, { method: 'DELETE' });
      setProjects(await api('/projects'));
    } catch (e) {
      setError(e.message);
    }
  }

  // Starts a build from a project page's branch list with the first allowed image and the default command.
  async function startBranchBuild(projectId, buildBranch) {
    if (!images[0]) throw new Error('No images are allowed for builds yet.');
    await api(`/projects/${projectId}/builds`, { method: 'POST', body: JSON.stringify({ branch: buildBranch, image: images[0], command: DEFAULT_BUILD_COMMAND }) });
  }

  if (mode === 'public') return <PublicHome branding={branding || DEFAULT_BRANDING} stats={stats} statsError={statsError} logoUrl={API + '/branding/logo'} onSignIn={() => keycloak.login()} dark={dark} setDark={setDark}>
    {projectName !== null && (buildId !== null
      ? <ProjectBuild key={`${projectName}-${projectBranch}-${buildId}`} name={projectName} branch={projectBranch} buildId={buildId} load={publicJson} onBack={() => openProject(projectName, projectBranch)}/>
      : <ProjectBuilds key={projectName} name={projectName} initialBranch={projectBranch} load={publicJson} onSignIn={() => keycloak.login()} onOpenBuild={openBuild}/>)}
  </PublicHome>;
  if (!ready) return <main className="loading-state" role={error ? 'alert' : 'status'}>{error || 'Connecting to Cisys…'}</main>;
  const canCreate = user.roles.includes('lead') || user.roles.includes('admin');
  // Admins may delete any project; leads only the projects they own.
  const canDelete = (project) => user.roles.includes('admin') || (user.roles.includes('lead') && project.owned);
  const role = user.roles.includes('admin') ? 'Admin' : user.roles.includes('lead') ? 'Lead' : 'Member';

  return (
    <div className="app-shell" style={branding.background ? { backgroundImage: `linear-gradient(rgba(0,0,0,.08), rgba(0,0,0,.08)), url(${branding.background})`, backgroundSize: 'cover', backgroundAttachment: 'fixed' } : undefined}>
      <header className="app-header">
        <div className="header-main">
          <a className="brand" href="/" aria-label={`${branding.name} home`} onClick={(event) => { event.preventDefault(); go('home'); void loadPublicStats(); }}><img src={branding.logo || headerLogo} alt="" className={branding.logo ? 'brand-logo' : 'brand-logo brand-logo-default'}/><span>{branding.name}</span></a>
          <nav aria-label="Primary"><button type="button" className="nav-link" aria-current={projectName === null && page === 'projects' ? 'page' : undefined} onClick={() => go('projects')}>Projects</button>{user.roles.includes('admin') && <button type="button" className="nav-link" aria-current={projectName === null && page === 'images' ? 'page' : undefined} onClick={() => go('images')}>Images</button>}{user.roles.includes('admin') && <button type="button" className="nav-link" aria-current={projectName === null && page === 'branding' ? 'page' : undefined} onClick={() => go('branding')}>Branding</button>}{user.roles.includes('admin') && <button type="button" className="nav-link" aria-current={projectName === null && page === 'settings' ? 'page' : undefined} onClick={() => go('settings')}>Settings</button>}</nav>
        </div>
        <div className="header-actions">
          <button type="button" className="theme-toggle" onClick={() => setDark(!dark)} aria-label="Toggle dark mode" aria-pressed={dark} title="Toggle dark mode">
            <Sun size={15} aria-hidden="true"/><Moon size={15} aria-hidden="true"/><span className="theme-knob"/>
          </button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="user-menu-trigger" aria-label="Account menu" title="Account menu">
                <CircleUserRound size={22} aria-hidden="true"/>
                <ChevronDown size={14} aria-hidden="true"/>
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => go('profile')}><UserRound aria-hidden="true"/> Profile</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => keycloak.logout({ redirectUri: window.location.origin + '/' })}><LogOut aria-hidden="true"/> Sign out</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      {projectName !== null ? (buildId !== null
        ? <ProjectBuild key={`${projectName}-${projectBranch}-${buildId}`} name={projectName} branch={projectBranch} buildId={buildId} load={api} onBack={() => openProject(projectName, projectBranch)}/>
        : <ProjectBuilds key={projectName} name={projectName} initialBranch={projectBranch} load={api} onOpenBuild={openBuild} onBuild={canCreate ? startBranchBuild : undefined}/>) : page === 'home' ? <main id="home" className="page-container">
        <PublicStats stats={stats} statsError={statsError}/>
      </main> : page === 'profile' ? <main id="profile" className="page-container">
        <PageHeader title="Profile"/>
        <Card className="project-card">
          <CardHeader><CardTitle>{role}</CardTitle><CardDescription>Signed in as {user.username}</CardDescription></CardHeader>
        </Card>
      </main> : page === 'images' && user.roles.includes('admin') ? <ImagesPanel api={api} onSaved={setImages}/> : page === 'branding' && user.roles.includes('admin') ? <BrandingPanel branding={branding} save={async (value) => { const saved = await api('/branding', { method: 'PUT', body: JSON.stringify(value) }); setBranding(saved); return saved; }}/> : page === 'settings' && user.roles.includes('admin') ? <SettingsPanel api={api}/> : <main id="projects" className="page-container">
        <PageHeader title="Projects" actions={<Badge variant="outline">{projects.length} {projects.length === 1 ? 'project' : 'projects'}</Badge>}/>
        {error && <div className="error-message" role="alert">{error}</div>}
        {canCreate && (
          <Card className="project-card">
            <CardHeader><CardTitle>Add a project</CardTitle></CardHeader>
            <CardContent>
              <form className="project-grid project-add" onSubmit={create}>
                <div className="form-field"><Label htmlFor="project-name">Project name</Label><Input id="project-name" required maxLength="100" pattern="[A-Za-z0-9][A-Za-z0-9._\-]*" title="Project names use letters, digits, '.', '_' and '-', and start with a letter or digit" value={name} onChange={(e) => setName(e.target.value)} placeholder="my-project"/></div>
                <div className="form-field"><Label htmlFor="repository-url">Repository URL</Label><Input id="repository-url" required type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://github.com/org/repo.git"/></div>
                <div className="form-field"><Label htmlFor="project-private" className="whitespace-nowrap">Private project</Label><span className="private-choice"><input id="project-private" type="checkbox" checked={priv} onChange={(e) => setPriv(e.target.checked)}/></span></div>
                <Button type="submit" size="icon" className="h-10 w-10 justify-self-end" disabled={submitting} title="Add project" aria-label="Add project"><Plus size={16} aria-hidden="true"/></Button>
              </form>
            </CardContent>
          </Card>
        )}
        <Card className="project-card">
          <CardHeader><CardTitle>Repositories</CardTitle></CardHeader>
          <CardContent>
            {projects.length === 0 ? (
              <div className="empty-state"><FolderGit2 size={28} aria-hidden="true"/><strong>No projects yet</strong>{!canCreate && <p>Ask a lead or admin to add a project.</p>}</div>
            ) : projects.map((project) => (
              <div className="project-grid project-row" key={project.id}>
                <strong className="project-name"><a href={projectPath(project.name)} onClick={(event) => { event.preventDefault(); openProject(project.name); }}>{project.name}</a></strong>
                <p className="project-url">{project.repositoryUrl}</p>
                <Badge variant="outline" className="justify-self-start">{project.privateRepository ? <LockKeyhole size={12} className="text-red-600 dark:text-red-400" aria-hidden="true"/> : <LockKeyholeOpen size={12} className="text-green-600 dark:text-green-400" aria-hidden="true"/>}{project.privateRepository ? 'Private' : 'Public'}</Badge>
                {canDelete(project) && <Button type="button" size="icon" className="h-10 w-10 justify-self-end" title="Delete project" aria-label={`Delete project ${project.name}`} onClick={() => void deleteProject(project)}><Trash2 size={16} aria-hidden="true"/></Button>}
              </div>
            ))}
          </CardContent>
        </Card>
      </main>}
      <AppFooter />
    </div>
  );
}

createRoot(document.getElementById('root')).render(<App/>);
