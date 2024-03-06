package io.proj3ct.SilveoSBot.service;

import io.proj3ct.SilveoSBot.config.BotConfig;
import io.proj3ct.SilveoSBot.model.User;
import io.proj3ct.SilveoSBot.model.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.aspectj.util.FileUtil;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideoNote;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ws.schild.jave.*;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;
    final BotConfig config;
    static final String HELP_TEXT = """
            Краткая информация по использованию бота:
            1. Отправьте видео, чтобы бот переделал его в кружок и отправил вам.
            2. Если вы хотите переслать это сообщение кому-то другому так, чтобы не было видно отправителя,
            вам нужно убрать пункт "отображать отправителя".
            """;

    public TelegramBot(BotConfig config){
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start", "Начало работы с ботом"));
        listOfCommands.add(new BotCommand("/mydata", "Моя информация"));
        listOfCommands.add(new BotCommand("/deletedata", "Удалить информацию"));
        listOfCommands.add(new BotCommand("/help", "Краткая сводка о работе с ботом"));
        try{
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException exception) {
            log.error("Error occurred while setting bot commands list: " + exception);
        }
    }

    public String getBotToken(){
        return config.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if(update.hasMessage() && update.getMessage().hasText()){ //Обработка текста
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            switch(messageText){
                case "/start":
                        registerUser(update.getMessage());
                        startCommandRecieved(chatId, update.getMessage().getChat().getFirstName());
                        break;
                case "/help":
                        sendMessage(chatId, HELP_TEXT);
                        break;
                default:
                    sendMessage(chatId, "Сообщение не распознано! :(");
            }
        }

        if(update.hasMessage() && update.getMessage().hasVideo()){ //Обработка видео
            update.getMessage().getVideo().setHeight(639);
            update.getMessage().getVideo().setWidth(639);
            if(update.getMessage().getVideo().getDuration() > 59)
                update.getMessage().getVideo().setDuration(59);
            String videoFileId = update.getMessage().getVideo().getFileId();
            String videoFileName = update.getMessage().getVideo().getFileName();
            try {
                downloadToDisk(videoFileId, videoFileName, update.getMessage().getChatId());
            } catch (IOException | JSONException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void registerUser(Message msg){
        if(userRepository.findById(msg.getChatId()).isEmpty()){
            var chatId = msg.getChatId();
            var chat = msg.getChat();

            User user = new User();
            user.setChatId(chatId);
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUsername(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));

            userRepository.save(user);
            log.info("User saved: " + user);
        }
    }

    private void startCommandRecieved(long chatId, String name) {
        String answer = "Привет, " + name + ", ты лох ты лох бебе бебе";
        sendMessage(chatId, answer);
        log.info("Replied to user " + name);
    }

    private void downloadToDisk(String fileId, String fileName, long chatId) throws IOException, JSONException {
        URL url = new URL("https://api.telegram.org/bot" + config.getToken() + "/getFile?file_id=" + fileId);
        System.out.println(url.toString());
        log.info("URL Formed: " + url.toString());

        BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
        String getFileResponse = br.readLine();

        JSONObject jresult = new JSONObject(getFileResponse);
        JSONObject path = jresult.getJSONObject("result");
        String file_path = path.getString("file_path");
        System.out.println(file_path);
        System.out.println(fileName);

        File localFile = new File("src/main/resources/downloaded/" + fileName);
        InputStream is = new URL("https://api.telegram.org/file/bot" + config.getToken() + "/" + file_path).openStream();

        FileUtils.copyInputStreamToFile(is, localFile);

        br.close();
        is.close();

        System.out.println("Uploaded!");
        System.out.println();
        log.info("Uploaded file " + fileName);

        changeResolution(localFile, chatId, fileName);
    }

    private void changeResolution(File localFile, long chatId, String fileName) throws IOException {
        File result = new File("src/main/resources/downloaded/newFile.mp4");
        MultimediaObject multimediaObject = new MultimediaObject(localFile);
        VideoAttributes video = new VideoAttributes();
        video.setFrameRate(30);
        video.setCodec("h264");
        video.setSize(new VideoSize(639, 639));
        EncodingAttributes attributes = new EncodingAttributes();
        attributes.setFormat("mp4");
        attributes.setVideoAttributes(video);

        try{
            Encoder encoder = new Encoder();
            encoder.encode(multimediaObject, result, attributes);
        } catch (Exception e){
            log.error("Error occured: " + e);
        }

        sendCircle(chatId, result);
    }

    private void sendCircle(long chatId, File localFile) throws IOException {
        SendVideoNote sendVideoNote = new SendVideoNote(); //send
        sendVideoNote.setChatId(chatId); //chatID
        sendVideoNote.setLength(240);
        sendVideoNote.setVideoNote(new InputFile(localFile, "Circled"));
        sendVideoNote.setAllowSendingWithoutReply(true);

        try{ execute(sendVideoNote); } catch (TelegramApiException e){
            log.error("Error occurred: " + e);
        }
    }


    private void sendMessage(long chatId, String toSend){
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(toSend);

        try {
            execute(message);
        } catch (TelegramApiException e){
            log.error("Error occurred: " + e.getMessage());
        };
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }
}
