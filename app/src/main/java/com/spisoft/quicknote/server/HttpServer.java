package com.spisoft.quicknote.server;

import android.content.Context;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.spisoft.quicknote.Note;
import com.spisoft.quicknote.PreferenceHelper;
import com.spisoft.quicknote.databases.CacheManager;
import com.spisoft.quicknote.databases.KeywordsHelper;
import com.spisoft.quicknote.databases.NoteManager;
import com.spisoft.quicknote.databases.RecentHelper;
import com.spisoft.quicknote.editor.recorder.AudioRecorderJS;
import com.spisoft.quicknote.reminders.RemindersManager;
import com.spisoft.quicknote.editor.EditorView;
import com.spisoft.quicknote.utils.FileUtils;
import com.spisoft.quicknote.utils.PictureUtils;
import com.spisoft.quicknote.utils.ZipUtils;
import com.spisoft.sync.utils.FileLocker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.safety.Whitelist;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.util.ServerRunner;

import static com.spisoft.quicknote.databases.NoteManager.PREVIEW_HEIGHT;
import static com.spisoft.quicknote.databases.NoteManager.PREVIEW_WIDTH;

public class HttpServer extends NanoHTTPD {

    private static final String TAG = "HttpServer";
    private final Context mContext;
    private final String extractedNotePath;
    private String mCurrentNotePath;
    private List<String> mAuthorizedID;
    /**
     * logger to log to.
     */

    public static void main(String[] args) {
    }

    public HttpServer(Context ct) {
        super(0);
        mAuthorizedID = new ArrayList<>();
        ServerRunner.executeInstance(this);
        try {
            start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mContext = ct;
        extractedNotePath = mContext.getCacheDir().getAbsolutePath()+"/currentnote";
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String path = session.getUri();
        InputStream rinput = null;
        String fileMimeType = null;
        Map<String, List<String>> parms = session.getParameters();
        Response.IStatus status = Response.Status.OK;
        Log.d(TAG, "Path: "+path);
        Map<String, String> files = new HashMap<>();
        Map<String, List<String>> post = new HashMap<>();
        if(Method.POST.equals(method)) {
            try {
                session.parseBody(files);
                for (Map.Entry<String, String> entry : files.entrySet()) {
                    Log.d(TAG, entry.getKey() + ": " + entry.getValue());

                }
                post = session.getParameters();

            } catch (IOException e) {
                e.printStackTrace();
            } catch (ResponseException e) {
                e.printStackTrace();
            }
        }
        Log.d(TAG, "Post : "+post.toString());
        for (Map.Entry<String, List<String>> entry : post.entrySet()) {
            Log.d(TAG, entry.getKey());

        }
        Log.d(TAG, "Get : "+parms.toString());
        Log.d(TAG, "query params "+                session.getQueryParameterString());
        if(path!=null){
            if(path.contains("../") || path.equals(".."))
                return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
            Log.d("pathdebug","path: "+path);

            if(path.startsWith("/api/")){
                if(!(session.getHeaders().get("requesttoken")!=null && mAuthorizedID.contains(session.getHeaders().get("requesttoken")) || parms.get("requesttoken").get(0)!=null && mAuthorizedID.contains(parms.get("requesttoken").get(0))))
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
                if(session.getHeaders().get("requesttoken")!=null)
                    mAuthorizedID.remove(session.getHeaders().get("requesttoken"));
                else
                    mAuthorizedID.remove(parms.get("requesttoken").get(0));
                String subpath = path.substring("/api/".length());


                if(Method.GET.equals(method)) {
                    switch (subpath) {
                        case "note/open":
                            return openNote(parms.get("path").get(0));
                        case "note/open/tmpwave":

                            try {
                                return NanoHTTPD.newChunkedResponse(Response.Status.OK,  MimeTypeMap.getSingleton().getMimeTypeFromExtension("wav"),new FileInputStream(new File(AudioRecorderJS.TMP_WAV_PATH)));
                            } catch (FileNotFoundException e) {
                                return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "", "not found");
                            }
                        case "note/get_note":
                            Log.d(TAG, "note/get_note");
                            Log.d(TAG, "note/get_note"+parms.get("path").get(0));

                            return getNote(parms.get("path").get(0));
                        case "keywordsdb":
                            return getKeywordDB();
                      /*  case "recentdb":
                            try {
                                RecentHelper.getInstance(mContext).getJson().toString();
                                return NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(RecentHelper.getInstance(mContext).getJson().toString().getBytes()));

                            } catch (JSONException e) {
                                e.printStackTrace();
                            }*/
                        case "settings/editor_css":
                            String theme = PreferenceManager.getDefaultSharedPreferences(mContext).getString("theme","carnet");
                            String metadata = FileUtils.readFile(mContext.getFilesDir().getAbsolutePath() +"/reader/css/"+theme+"/metadata.json");
                            try {
                                JSONObject metadatajson = new JSONObject(metadata);
                                JSONArray array = metadatajson.getJSONArray("editor");
                                for (int i = 0; i<array.length(); i++){
                                    array.put(i, "../reader/css/"+theme+"/"+array.getString(i));
                                }
                                Log.d(TAG, metadata);
                                return NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(array.toString().getBytes()));

                            } catch (JSONException e) {
                                e.printStackTrace();
                            }

                              case "note/open/0/listMedia":
                            return listOpenMedia();
                        case "recorder/encoderWorker.min.wasm":
                        case "recorder/decoderWorker.min.wasm":
                        case "recorder/encoderWorker.min.js":
                        case "recorder/decoderWorker.min.js": {
                            File f = new File(mContext.getFilesDir(), "reader/reader/libs/" + subpath);
                            return NanoHTTPD.newChunkedResponse(Response.Status.OK, subpath.endsWith("wasm") ? "application/wasm" : "application/javascript", new ByteArrayInputStream(FileUtils.readFile(f.getAbsolutePath()).getBytes()));
                        }
                        case "settings/lang/json":
                            String lang = parms.get("lang").get(0);
                            if(lang.contains("../"))
                                return null;
                            File f = new File(mContext.getFilesDir(), "reader/i18n/"+lang+".json");
                            return NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(FileUtils.readFile(f.getAbsolutePath()).getBytes()));

                    }
                    if(subpath.startsWith("note/open/0/getMedia/")){
                        return getMedia(subpath.substring("note/open/0/getMedia/".length()));
                    }
                }
                else if(Method.POST.equals(method)){
                    switch (subpath) {
                        case "note/import_archive": {
                            /* TODO
                                implement note folder import
                             */

                            JSONObject response = new JSONObject();

                            String tmpPath = files.get("media[]");
                            try {
                                JSONArray renamedResponse = new JSONArray();
                                JSONArray log = new JSONArray();
                                try {
                                    response.put("renamed",renamedResponse);
                                    response.put("log", log);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }

                                ZipFile zipFile = new ZipFile(tmpPath);
                                ZipEntry entry = null;
                                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                                String prefix = "";
                                String carnetPath = PreferenceHelper.getRootPath(mContext);
                                while (entries.hasMoreElements()) {
                                    entry = entries.nextElement();
                                    int i = entry.getName().indexOf("/");
                                    if (i <= 0) {
                                        if (!entry.isDirectory()) {
                                            prefix = null;
                                        }
                                    } else {
                                        String curPrefix = entry.getName().substring(0, i);
                                        if (prefix != null && prefix.isEmpty()) {
                                            prefix = curPrefix;
                                        } else if (!curPrefix.equals(prefix)) {
                                            prefix = null;
                                            break;
                                        }
                                    }
                                }
                                List<String> keywordsDB = new ArrayList<>();
                                List<String> recentDB = new ArrayList<>();
                                Map<String, String> renamed = new HashMap<>();
                                entries = zipFile.entries();
                                while (entries.hasMoreElements()) {
                                    entry = entries.nextElement();
                                    String relativePath = entry.getName();
                                    if (prefix != null) {
                                        relativePath = relativePath.substring((prefix + "/").length());
                                    }
                                    if (relativePath.isEmpty())
                                        continue;
                                    if (relativePath.endsWith(".sqd")) {
                                        Log.d("importdebug",relativePath);
                                        String newPath = relativePath;
                                        if (new File(carnetPath, relativePath).exists()) {
                                            newPath = newPath.substring(0, newPath.length() - ".sqd".length())+NoteManager.randomChar()+NoteManager.randomChar()+".sqd";
                                            Log.d("importdebug","file already there");

                                        }
                                        if (!entry.isDirectory()) {
                                            File out = new File(carnetPath, newPath);
                                            out.getParentFile().mkdirs();
                                            FileOutputStream stream = new FileOutputStream(out);
                                            FileUtils.copy(zipFile.getInputStream(entry), stream);
                                            if (!newPath.equals(relativePath)) {

                                                String md5new = FileUtils.md5(out.getAbsolutePath());
                                                String md5old = FileUtils.md5(new File(carnetPath, relativePath).getAbsolutePath());
                                                if (md5new.equals(md5old)) {
                                                    out.delete();
                                                    Log.d("importdebug","is same file");

                                                } else {
                                                    log.put("new file (will need to rename  "+newPath);
                                                    Log.d("importdebug","is new file");
                                                    renamedResponse.put(newPath);
                                                    renamed.put(relativePath, newPath);
                                                }
                                            } else {
                                                log.put("new file  "+newPath);
                                            }
                                        }

                                    } else if (relativePath.startsWith("quickdoc")) {
                                        if (relativePath.startsWith("quickdoc/keywords/")) {
                                            keywordsDB.add(entry.getName());
                                        } else if (relativePath.startsWith("quickdoc/recentdb/")) {
                                            recentDB.add(entry.getName());
                                        }
                                    }
                                }


                                for (String dbPath : keywordsDB) {
                                    String contents = FileUtils.readInputStream(zipFile.getInputStream(zipFile.getEntry(dbPath)));

                                    try {
                                        JSONObject thisKeywordsDB = new JSONObject(contents);
                                        for (Map.Entry<String, String> entry1 : renamed.entrySet()) {
                                            JSONArray data = thisKeywordsDB.getJSONArray("data");
                                            for (int i = 0; i < data.length(); i++) {
                                                JSONObject item = data.getJSONObject(i);
                                                if (item.getString("path").equals(entry1.getKey())) {
                                                    item.put("path", entry1.getValue());
                                                }
                                            }
                                        }
                                        log.put("merging with "+dbPath);
                                        log.put("has changed ? "+ KeywordsHelper.getInstance(mContext).mergeDB(thisKeywordsDB));
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }


                                }
                                for (String dbPath : recentDB) {
                                    String contents = FileUtils.readInputStream(zipFile.getInputStream(zipFile.getEntry(dbPath)));

                                    try {
                                        JSONObject thisRecentDB = new JSONObject(contents);
                                        for (Map.Entry<String, String> entry1 : renamed.entrySet()) {
                                            JSONArray data = thisRecentDB.getJSONArray("data");
                                            for (int i = 0; i < data.length(); i++) {
                                                JSONObject item = data.getJSONObject(i);
                                                if (item.getString("path").equals(entry1.getKey())) {
                                                    item.put("path", entry1.getValue());
                                                }
                                            }
                                        }
                                        RecentHelper.getInstance(mContext).mergeDB(thisRecentDB);
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                    }


                                }


                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            return  NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(response.toString().getBytes()));
                        }
                        case "note/import":
                            String relativeNotePath = (post.get("path").get(0) == null || post.get("path").get(0) == "" ? "" :
                                    (post.get("path").get(0) + "/")) + post.get("media[]").get(0);

                            String tmpPath = files.get("media[]");
                            File noteFile = new File(PreferenceHelper.getRootPath(mContext)+"/"+relativeNotePath);
                            boolean success = FileUtils.move(tmpPath, PreferenceHelper.getRootPath(mContext)+"/"+relativeNotePath);

                            if(success){
                                try {
                                    JSONObject metadata = new JSONObject(post.get("metadata").get(0));
                                    JSONArray keywords = metadata.getJSONArray("keywords");
                                    for (int i = 0; i<keywords.length(); i++){
                                        KeywordsHelper.getInstance(mContext).addKeyword(keywords.getString(i), new Note(noteFile.getAbsolutePath()));
                                    }
                                    if(post.get("add_to_recent").get(0).equals("true")){
                                        Note note = new Note(noteFile.getAbsolutePath());
                                        RecentHelper.getInstance(mContext).addNote(note);
                                        if(post.get("is_pinned").get(0).equals("true"))
                                            RecentHelper.getInstance(mContext).pin(note);

                                    }
                                    success = true;
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                            return success?NanoHTTPD.newFixedLengthResponse("Saved !"):NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"","");
                        case "notes/move":
                            String from = post.get("from").get(0);
                            String to = post.get("to").get(0);
                            if(from.startsWith("./"))
                                from = from.substring(2);
                            if(to.startsWith("./"))
                                to = to.substring(2);
                            return moveNote(from, to);
                        case "keywordsdb/action":
                            if(post.get("json") != null && post.get("json").size()>0)
                               return keywordActionDB(post.get("json").get(0));
                        case "note/saveText":
                            if (post.get("path") != null && post.get("path").size() >= 0 && post.get("html") != null && post.get("html").size() >= 0 && post.get("metadata") != null && post.get("metadata").size() >= 0)
                                return saveNote(post.get("path").get(0), post.get("html").get(0), post.get("metadata").get(0));
                        case "note/open/0/addMedia":
                            if (post.get("path").size() > 0 && post.get("media[]").size() > 0 && files.containsKey("media[]"))
                                return addMedia(post.get("path").get(0), files.get("media[]"), post.get("media[]").get(0));

                    }
                } else if(Method.DELETE.equals(method)){
                    switch (subpath) {
                        case "note/open/0/media":
                            return deleteMedia(parms.get("path").get(0), parms.get("media").get(0));

                    }

                }

            }
            else {
                if(path.contains("../"))
                    return null;
                fileMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(path));
                try {
                    if(path.equals("/reader/reader/reader.html")){

                    } else if (path.equals("/reader/index.html")){
                        String index = FileUtils.readFile(mContext.getFilesDir().getAbsolutePath() + path);
                        index = index.replace("!API_URL","/api/");
                        rinput = new ByteArrayInputStream(index.getBytes());
                    }
                    else
                        rinput = new FileInputStream(mContext.getFilesDir().getAbsolutePath() + path);

                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    status = Response.Status.NOT_FOUND;
                }
            }

        }

        return NanoHTTPD.newChunkedResponse(status, fileMimeType, rinput);
    }

    private Response deleteMedia(String path, String media) {
        if(media.contains("../") || media.equals(".."))
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
        if(!mCurrentNotePath.equals(path)){
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
        }
        File f = new File(extractedNotePath+"/data/"+media);
        if(f.delete()){
            if(PictureUtils.isPicture(f.getName())) {
                new File(f.getParentFile(), "preview_" + f.getName() + ".jpg").delete();
            }
        }

        String absolutePath = getFullNotePath(path);

        if(isFileNote(absolutePath))
            saveNote(path);
        else {
            f = new File(absolutePath+"/data/"+media);
            if(f.delete()){
                if(PictureUtils.isPicture(f.getName())) {
                    new File(f.getParentFile(), "preview_" + f.getName() + ".jpg").delete();
                }
            }
        }
        return listOpenMedia();
    }

    private boolean isFileNote(String path){
        File file = new File(path);
        boolean asFolder = PreferenceHelper.createNoteAsFolder(mContext);
        if(!file.exists() && !asFolder || file.isFile()) {
            return true;
        }
        return false;
    }

    public void setCurrentNotePath(String path){
        mCurrentNotePath = path;
    }

    private Response keywordActionDB(String jason) {
        try {
            JSONArray object = new JSONArray(jason);
            for (int i = 0; i < object.length(); i++){
                if(object.getJSONObject(i).getString("action").equals("add")){
                    KeywordsHelper.getInstance(mContext).addKeyword(object.getJSONObject(i).getString("keyword"), new Note(object.getJSONObject(i).getString("path")));
                } else if (object.getJSONObject(i).getString("action").equals("remove")){
                    KeywordsHelper.getInstance(mContext).removeKeyword(object.getJSONObject(i).getString("keyword"), new Note(object.getJSONObject(i).getString("path")));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return getKeywordDB();
    }

    private Response addMedia(String path, String tmpPath, String name) {
        if(name.contains("../") || name.equals(".."))
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
        if(!mCurrentNotePath.equals(path)){
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
        }
        Log.d(TAG, "adding media "+name);
        //TODO fix data.... workaround until better idea
        File data = new File(extractedNotePath+"/data");
        if(data.exists() && !data.isDirectory())
            data.delete();
        File in = new File(tmpPath);
        if(in.exists()){
            if(FileUtils.getExtension(name) == null){
                //bad fix to retieve extension...
                name = System.currentTimeMillis()+"."+EditorView.sNextExtension;

            }
            List files = new ArrayList();
            files.add(new File("data",name).getAbsolutePath());

            File newF = new File(data, name);

            newF.getParentFile().mkdirs();
            Log.d(TAG, "rename "+tmpPath+ " to "+newF.getAbsolutePath()+": "+ in.renameTo(newF));
            if(PictureUtils.isPicture(name)) {
                File preview = new File(data, "preview_" + name +".jpg");
                files.add(new File("data","preview_" + name +".jpg").getAbsolutePath());

                try {
                    PictureUtils.resize(newF.getAbsolutePath(), preview.getAbsolutePath(), PREVIEW_WIDTH, PREVIEW_HEIGHT);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            String absolutePath = getFullNotePath(path);
            if(isFileNote(absolutePath))
                saveNote(path);
            else {

                try {
                    Log.d(TAG, "is folder");
                    Note note = new Note(absolutePath);
                    saveFilesToNote(files, note, null);
                } catch (NoteSaveException e) {
                    e.printStackTrace();
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"",e.getMessage());
                }
            }
        }
        return listOpenMedia();
    }

    private Response getMedia(String name){
        try {
            return  NanoHTTPD.newChunkedResponse(Response.Status.OK,  MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(name)),new FileInputStream(new File(new File(extractedNotePath, "data"), name)));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        };
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "", "not found");

    }



    private Response listOpenMedia() {
        JSONArray array = new JSONArray();
        File f = new File(extractedNotePath, "data");
        if(f.exists()) {
            for(File c : f.listFiles()){
                if(!c.getName().startsWith("preview_")){
                    array.put("/api/note/open/0/getMedia/"+c.getName());
                }
            }
        }
        return  NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(array.toString().getBytes()));
    }

    private Response saveNote(String path, String html, String metadata) {
        if(!mCurrentNotePath.equals(path)){
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
        }
        FileUtils.writeToFile(extractedNotePath+"/index.html", html);
        FileUtils.writeToFile(extractedNotePath+"/metadata.json", metadata);
        //we update metadata cache
        File noteFile = new File(PreferenceHelper.getRootPath(mContext),path);
        Note note = new Note(noteFile.getAbsolutePath());
        note.mMetadata = Note.Metadata.fromString(metadata);
        

        String txt = NoteManager.getShortText(html, 100);
        note.shortText = txt;

        List files = new ArrayList();
        files.add("metadata.json");
        files.add("index.html");
        try {
            saveFilesToNote(files, note, null);
        } catch (NoteSaveException e) {
            e.printStackTrace();
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"",e.getMessage());
        }


        return NanoHTTPD.newFixedLengthResponse("Saved !");
    }

    class NoteSaveException extends Exception{

    }

    private boolean saveFilesToNote(List<String> files, Note note, String relativePath) throws NoteSaveException{
        if(note == null){
            File noteFile = new File(PreferenceHelper.getRootPath(mContext),relativePath);
            note = CacheManager.getInstance(mContext).get(noteFile.getAbsolutePath());
            if(note == null){
                note = new Note(noteFile.getAbsolutePath());
            }
        }

        File file = new File(note.path);
        boolean asFolder = PreferenceHelper.createNoteAsFolder(mContext);
        if(isFileNote(note.path)) {
            saveNote(note);
            return false;
        }
        else{
            for(String filePath :files){
                try {

                    File out = new File(note.path, filePath);
                    out.getParentFile().mkdirs();
                    FileUtils.copy(new FileInputStream(new File(extractedNotePath, filePath)), new FileOutputStream(out));
                } catch (IOException e) {
                   throw new NoteSaveException();
                }
            }
            refreshCache(note);
            return true;
        }


    }

    private String getFullNotePath(String relativePath){
        File noteFile = new File(PreferenceHelper.getRootPath(mContext),relativePath);
        return noteFile.getAbsolutePath();
    }

    public Response saveNote(String relativePath) {
        File noteFile = new File(getFullNotePath(relativePath));
        Note note = CacheManager.getInstance(mContext).get(noteFile.getAbsolutePath());
        if(note == null){
            note = new Note(noteFile.getAbsolutePath());
        }
        return saveNote(note);
    }

    public Response saveNote(Note note) {
        List <String> except = new ArrayList<>();
        except.add(extractedNotePath+"/reader.html");
        File file = new File(note.path);
        boolean asFolder = PreferenceHelper.createNoteAsFolder(mContext);
        if(!file.exists() && !asFolder || file.isFile())
            ZipUtils.zipFolder(new File(extractedNotePath), note.path, except);
        else
            FileUtils.copyDirectoryOneLocationToAnotherLocation(new File(extractedNotePath),file);

        refreshCache(note);

        return NanoHTTPD.newFixedLengthResponse("Saved !");
    }

    private void refreshCache(Note note){
        File noteFile = new File(PreferenceHelper.getRootPath(mContext),note.path);
        File f = new File(extractedNotePath, "data");
        note.previews.clear();
        if(f.exists()) {
            if(!f.isDirectory())
                f.delete();
            else {
                for (File c : f.listFiles()) {
                    if (c.getName().startsWith("preview_")) {
                        note.previews.add("data/"+c.getName());
                    } else{
                        note.medias.add("data/"+c.getName());
                    }
                }
            }
        }
        note.file_lastmodification = noteFile.lastModified();
        CacheManager.getInstance(mContext).addToCache(note);
        CacheManager.getInstance(mContext).writeCache();
        RemindersManager.Companion.getInstance(mContext).add(note);
    }

    private Response moveNote(String from, String to) {
        Log.d(TAG, "MoveNote "+from +" to "+to);
        if(!mCurrentNotePath.equals(from)){
            Log.d(TAG, "Forbidden");

            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"","");
        }
        boolean success = NoteManager.moveNote(mContext, new Note(new File(PreferenceHelper.getRootPath(mContext),from).getAbsolutePath()),  new File(PreferenceHelper.getRootPath(mContext),to).getAbsolutePath()) != null;
        if(success)
            mCurrentNotePath = to;
        Log.d(TAG, "MoveNote "+success);

        return success?NanoHTTPD.newFixedLengthResponse("Saved !"):NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"","");
    }

    private Response getKeywordDB() {
        JSONObject object = null;
        try {
            object = KeywordsHelper.getInstance(mContext).getJson();
            return  NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(object.toString().getBytes()));

        } catch (JSONException e) {
            e.printStackTrace();
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,"","");
    }

    private Response getNote(String path) {
        Log.d("getnotedebug","getNote");
        if (!mCurrentNotePath.equals(path)) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "", "");
        }
        Log.d("getnotedebug","getNote "+path);
        File note = new File(PreferenceHelper.getRootPath(mContext), path);
        path = note.getAbsolutePath();
        try {
            Log.d("getnotedebug","getNote return"+path);
            if(note.isDirectory()){
                //first, zip it.
                File tmpExport = new File(mContext.getCacheDir(), "tmp_export.sqd");
                tmpExport.delete();
                ZipUtils.zipFolder(note,tmpExport.getAbsolutePath(), new ArrayList<>());
                return  NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/zip", new FileInputStream(tmpExport));
            }
            else
                return  NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/zip", new FileInputStream(path));
        } catch (FileNotFoundException e) {
            Log.d("getnotedebug","getNote ext"+path);

            e.printStackTrace();
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,"","");
    }
    private Response openNote(String path) {

        if(!mCurrentNotePath.equals(path)){
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"","");
        }
        path = new File(PreferenceHelper.getRootPath(mContext),path).getAbsolutePath();
        Log.d(TAG, "opening note "+path);
        try {
            File dir = new File(extractedNotePath);
            List<String> except =new ArrayList();
            except.add(extractedNotePath + "/reader.html");
            FileUtils.deleteRecursive(dir, except);
            JSONObject object = new JSONObject();
            object.put("id","0");
            File noteFile = new File(path);
            if(noteFile.exists()) {
                if(noteFile.isFile())
                    ZipUtils.unzip(path, extractedNotePath);
                else
                    FileUtils.copyDirectoryOneLocationToAnotherLocation(noteFile, new File(extractedNotePath))
                            ;
                File f = new File(extractedNotePath, "index.html");
                if (f.exists()) {
                    String index = FileUtils.readFile(f.getAbsolutePath());
                    object.put("html",index);
                }
                f = new File(extractedNotePath, "metadata.json");
                if (f.exists()) {
                    String meta = FileUtils.readFile(f.getAbsolutePath());
                    object.put("metadata",new JSONObject(meta));
                }

            } else object.put("error","not found");

            return  NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/json",new ByteArrayInputStream(object.toString().getBytes()));



        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,"","");
    }

    public String getUrl(String path){
        int port = getListeningPort();
        String url = "http://localhost:"+port+path;
        Log.d(TAG, url);
        return url;
    }

    public String generateID(){
        String id = UUID.randomUUID().toString();
        mAuthorizedID.add(id);
        return id;
    }

    public String getCurrentPath(){
        return mCurrentNotePath;
    }
}
